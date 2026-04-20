package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes Stripe webhook events (BILL-12). Exactly-once semantics are guaranteed by the
 * {@code stripe_events.stripe_event_id} UNIQUE constraint: before dispatching, the processor
 * inserts a row for the event id; if the row already exists the event is silently ignored.
 *
 * <p>Handled event types:
 * <ul>
 *   <li>{@code checkout.session.completed} — persist the Stripe customer id on the user so later
 *       subscription events can be attributed.</li>
 *   <li>{@code customer.subscription.created} / {@code customer.subscription.updated} —
 *       upsert the local {@link SubscriptionEntity} and reflect the plan on the {@link User}
 *       record.</li>
 *   <li>{@code customer.subscription.deleted} — downgrade the user back to {@link Plan#FREE}.
 *   </li>
 *   <li>{@code invoice.payment_failed} — flag the user's {@code planStatus} as
 *       {@code PAYMENT_FAILED} without immediately revoking the plan; a follow-up
 *       subscription event will eventually downgrade.</li>
 * </ul>
 *
 * <p>All unknown event types are logged at DEBUG level and acknowledged (marked processed) so
 * Stripe stops retrying.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookProcessor {

  /** User.planStatus value when a subscription payment has failed. */
  static final String PAYMENT_FAILED_STATUS = "PAYMENT_FAILED";

  private final StripeEventRepository stripeEventRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final UserRepository userRepository;
  private final StripeProperties stripeProperties;

  /**
   * Idempotently processes a verified Stripe {@link Event}. Returns {@code true} when this is the
   * first time the event is seen (and therefore was dispatched), {@code false} when the event id
   * was already recorded and nothing was done.
   */
  @Transactional
  public boolean process(Event event) {
    if (event == null || event.getId() == null || event.getId().isBlank()) {
      log.warn("Ignoring Stripe webhook with missing event id");
      return false;
    }
    Optional<StripeEventEntity> existing =
        stripeEventRepository.findByStripeEventId(event.getId());
    if (existing.isPresent()) {
      log.debug(
          "Stripe event {} already processed at {}; skipping",
          event.getId(),
          existing.get().getProcessedAt());
      return false;
    }
    StripeEventEntity record = new StripeEventEntity();
    record.setStripeEventId(event.getId());
    record.setType(event.getType() == null ? "" : event.getType());
    record.setPayload(event.toJson() == null ? "{}" : event.toJson());
    stripeEventRepository.save(record);

    dispatch(event);

    record.setProcessedAt(Instant.now());
    stripeEventRepository.save(record);
    return true;
  }

  private void dispatch(Event event) {
    String type = event.getType() == null ? "" : event.getType();
    switch (type) {
      case "checkout.session.completed" -> handleCheckoutCompleted(event);
      case "customer.subscription.created", "customer.subscription.updated" ->
          handleSubscriptionUpsert(event);
      case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
      case "invoice.payment_failed" -> handlePaymentFailed(event);
      default -> log.debug("Ignoring unhandled Stripe event type '{}' (id={})", type, event.getId());
    }
  }

  private void handleCheckoutCompleted(Event event) {
    Session session = deserialize(event, Session.class);
    if (session == null) {
      return;
    }
    String customerId = session.getCustomer();
    User user = resolveUser(session.getClientReferenceId(), customerId);
    if (user == null) {
      log.warn(
          "checkout.session.completed {}: no user found (clientReferenceId={}, customer={})",
          event.getId(),
          session.getClientReferenceId(),
          customerId);
      return;
    }
    // Persist the customer id so subsequent subscription/invoice events (which only reference the
    // Stripe customer) can be attributed. The full plan activation is performed by the follow-up
    // customer.subscription.created event.
    if (customerId != null
        && !customerId.isBlank()
        && !customerId.equals(user.getStripeCustomerId())) {
      user.setStripeCustomerId(customerId);
      userRepository.save(user);
    }
    log.info(
        "checkout.session.completed {}: linked Stripe customer {} to user id={}",
        event.getId(),
        customerId,
        user.getId());
  }

  private void handleSubscriptionUpsert(Event event) {
    Subscription subscription = deserialize(event, Subscription.class);
    if (subscription == null) {
      return;
    }
    User user = resolveUserByCustomer(subscription.getCustomer());
    if (user == null) {
      log.warn(
          "{} {}: no user found for customer {}",
          event.getType(),
          event.getId(),
          subscription.getCustomer());
      return;
    }
    String priceId = firstPriceId(subscription);
    Plan plan = stripeProperties.resolvePlanFromPriceId(priceId);
    if (plan == null) {
      log.warn(
          "{} {}: unknown price id '{}' — leaving user plan unchanged",
          event.getType(),
          event.getId(),
          priceId);
      plan = user.getPlan() == null ? Plan.FREE : user.getPlan();
    }

    SubscriptionEntity entity =
        subscriptionRepository
            .findFirstByUserIdOrderByUpdatedAtDesc(user.getId())
            .filter(
                e ->
                    subscription.getId() != null
                        && subscription.getId().equals(e.getStripeSubscriptionId()))
            .orElseGet(SubscriptionEntity::new);
    entity.setUserId(user.getId());
    entity.setStripeSubscriptionId(subscription.getId());
    entity.setPlan(plan);
    entity.setStatus(subscription.getStatus() == null ? "unknown" : subscription.getStatus());
    entity.setCurrentPeriodStart(toInstant(firstItemField(subscription, SubscriptionItem::getCurrentPeriodStart)));
    entity.setCurrentPeriodEnd(toInstant(firstItemField(subscription, SubscriptionItem::getCurrentPeriodEnd)));
    entity.setCancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
    subscriptionRepository.save(entity);

    // Reflect the subscription state on the user. Only grant the plan while the subscription is
    // active/trialing; otherwise mirror the Stripe status so downstream gating (EntitlementService)
    // can observe e.g. "past_due" without us silently leaving FREE users on PREMIUM.
    String status = entity.getStatus();
    boolean active = "active".equalsIgnoreCase(status) || "trialing".equalsIgnoreCase(status);
    user.setPlan(active ? plan : Plan.FREE);
    user.setPlanStatus(status);
    user.setPlanValidUntil(entity.getCurrentPeriodEnd());
    userRepository.save(user);
    log.info(
        "{} {}: user id={} -> plan={} status={} until {}",
        event.getType(),
        event.getId(),
        user.getId(),
        user.getPlan(),
        status,
        user.getPlanValidUntil());
  }

  private void handleSubscriptionDeleted(Event event) {
    Subscription subscription = deserialize(event, Subscription.class);
    if (subscription == null) {
      return;
    }
    User user = resolveUserByCustomer(subscription.getCustomer());
    if (user == null) {
      log.warn(
          "customer.subscription.deleted {}: no user found for customer {}",
          event.getId(),
          subscription.getCustomer());
      return;
    }
    user.setPlan(Plan.FREE);
    user.setPlanStatus(
        subscription.getStatus() == null ? "canceled" : subscription.getStatus());
    user.setPlanValidUntil(null);
    userRepository.save(user);

    subscriptionRepository
        .findFirstByUserIdOrderByUpdatedAtDesc(user.getId())
        .filter(
            e ->
                subscription.getId() != null
                    && subscription.getId().equals(e.getStripeSubscriptionId()))
        .ifPresent(
            e -> {
              e.setStatus(
                  subscription.getStatus() == null ? "canceled" : subscription.getStatus());
              e.setCancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
              subscriptionRepository.save(e);
            });
    log.info(
        "customer.subscription.deleted {}: downgraded user id={} to FREE",
        event.getId(),
        user.getId());
  }

  private void handlePaymentFailed(Event event) {
    Invoice invoice = deserialize(event, Invoice.class);
    if (invoice == null) {
      return;
    }
    User user = resolveUserByCustomer(invoice.getCustomer());
    if (user == null) {
      log.warn(
          "invoice.payment_failed {}: no user found for customer {}",
          event.getId(),
          invoice.getCustomer());
      return;
    }
    user.setPlanStatus(PAYMENT_FAILED_STATUS);
    userRepository.save(user);
    log.info(
        "invoice.payment_failed {}: flagged user id={} as {}",
        event.getId(),
        user.getId(),
        PAYMENT_FAILED_STATUS);
  }

  private User resolveUser(String clientReferenceId, String customerId) {
    if (clientReferenceId != null && !clientReferenceId.isBlank()) {
      try {
        Long userId = Long.parseLong(clientReferenceId.trim());
        Optional<User> byId = userRepository.findById(userId);
        if (byId.isPresent()) {
          return byId.get();
        }
      } catch (NumberFormatException ignored) {
        // fall through to customer id lookup
      }
    }
    return resolveUserByCustomer(customerId);
  }

  private User resolveUserByCustomer(String customerId) {
    if (customerId == null || customerId.isBlank()) {
      return null;
    }
    return userRepository.findByStripeCustomerId(customerId).orElse(null);
  }

  private static String firstPriceId(Subscription subscription) {
    SubscriptionItem item = firstItem(subscription);
    if (item == null || item.getPrice() == null) {
      return null;
    }
    return item.getPrice().getId();
  }

  private static SubscriptionItem firstItem(Subscription subscription) {
    if (subscription == null
        || subscription.getItems() == null
        || subscription.getItems().getData() == null
        || subscription.getItems().getData().isEmpty()) {
      return null;
    }
    return subscription.getItems().getData().get(0);
  }

  private static Long firstItemField(
      Subscription subscription, java.util.function.Function<SubscriptionItem, Long> extractor) {
    SubscriptionItem item = firstItem(subscription);
    return item == null ? null : extractor.apply(item);
  }

  private static Instant toInstant(Long epochSeconds) {
    return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
  }

  @SuppressWarnings("unchecked")
  private <T extends StripeObject> T deserialize(Event event, Class<T> type) {
    EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
    if (deserializer == null) {
      log.warn("Stripe event {} has no data object deserializer", event.getId());
      return null;
    }
    Optional<StripeObject> object = deserializer.getObject();
    StripeObject resolved;
    if (object.isPresent()) {
      resolved = object.get();
    } else {
      // API version mismatch between Stripe and the SDK — deserializeUnsafe does best-effort
      // parsing and is the recommended fallback per the Stripe docs.
      try {
        resolved = deserializer.deserializeUnsafe();
      } catch (Exception ex) {
        log.warn(
            "Stripe event {} ({}): unable to deserialize payload: {}",
            event.getId(),
            event.getType(),
            ex.getMessage());
        return null;
      }
    }
    if (!type.isInstance(resolved)) {
      log.warn(
          "Stripe event {} ({}): expected {} but got {}",
          event.getId(),
          event.getType(),
          type.getSimpleName(),
          resolved == null ? "null" : resolved.getClass().getSimpleName());
      return null;
    }
    return (T) resolved;
  }
}
