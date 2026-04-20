package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeWebhookProcessorTest {

  @Mock private StripeEventRepository stripeEventRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private UserRepository userRepository;

  private StripeProperties stripeProperties;
  private StripeWebhookProcessor processor;
  private User user;

  @BeforeEach
  void setUp() {
    stripeProperties = new StripeProperties();
    stripeProperties.setPriceIdPremiumMonth("price_premium_month");
    stripeProperties.setPriceIdPremiumYear("price_premium_year");
    stripeProperties.setPriceIdMaxMonth("price_max_month");
    stripeProperties.setPriceIdMaxYear("price_max_year");

    user = new User();
    setId(user, 42L);
    user.setEmail("user@example.com");
    user.setPlan(Plan.FREE);
    user.setStripeCustomerId("cus_123");

    processor =
        new StripeWebhookProcessor(
            stripeEventRepository, subscriptionRepository, userRepository, stripeProperties);

    when(stripeEventRepository.save(any(StripeEventEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(stripeEventRepository.saveAndFlush(any(StripeEventEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void idempotency_skipsAlreadyProcessedEvents() {
    Event event = eventOfType("customer.subscription.updated", "evt_dup");
    when(stripeEventRepository.saveAndFlush(any(StripeEventEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    boolean processed = processor.process(event);

    assertThat(processed).isFalse();
    verify(stripeEventRepository, never()).save(any());
    verify(userRepository, never()).save(any());
  }

  @Test
  void checkoutSessionCompleted_linksCustomerToUser() {
    User fresh = new User();
    setId(fresh, 77L);
    fresh.setPlan(Plan.FREE);

    Session session = new Session();
    session.setClientReferenceId("77");
    session.setCustomer("cus_new");
    Event event = eventWithPayload("checkout.session.completed", "evt_cs_1", session);

    when(userRepository.findById(77L)).thenReturn(Optional.of(fresh));

    assertThat(processor.process(event)).isTrue();

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getStripeCustomerId()).isEqualTo("cus_new");
  }

  @Test
  void subscriptionUpdated_upsertsAndGrantsPlan() {
    long periodStart = 1_700_000_000L;
    long periodEnd = 1_702_678_400L;
    Subscription sub =
        activeSubscription(
            "sub_1", "cus_123", "price_premium_month", "active", periodStart, periodEnd);
    Event event = eventWithPayload("customer.subscription.updated", "evt_sub_1", sub);

    when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByStripeSubscriptionId("sub_1"))
        .thenReturn(Optional.empty());

    assertThat(processor.process(event)).isTrue();

    ArgumentCaptor<SubscriptionEntity> subCap = ArgumentCaptor.forClass(SubscriptionEntity.class);
    verify(subscriptionRepository).save(subCap.capture());
    assertThat(subCap.getValue().getPlan()).isEqualTo(Plan.PREMIUM);
    assertThat(subCap.getValue().getStatus()).isEqualTo("active");
    assertThat(subCap.getValue().getStripeSubscriptionId()).isEqualTo("sub_1");
    assertThat(subCap.getValue().getCurrentPeriodStart())
        .isEqualTo(Instant.ofEpochSecond(periodStart));
    assertThat(subCap.getValue().getCurrentPeriodEnd())
        .isEqualTo(Instant.ofEpochSecond(periodEnd));

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.PREMIUM);
    assertThat(userCap.getValue().getPlanStatus()).isEqualTo("active");
    assertThat(userCap.getValue().getPlanValidUntil())
        .isEqualTo(Instant.ofEpochSecond(periodEnd));
  }

  @Test
  void subscriptionUpdated_upsertsExistingRowByStripeSubscriptionId() {
    SubscriptionEntity existing = new SubscriptionEntity();
    existing.setUserId(42L);
    existing.setStripeSubscriptionId("sub_1");
    existing.setPlan(Plan.PREMIUM);
    existing.setStatus("trialing");

    Subscription sub = activeSubscription("sub_1", "cus_123", "price_max_month", "active");
    Event event = eventWithPayload("customer.subscription.updated", "evt_sub_3", sub);

    when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByStripeSubscriptionId("sub_1"))
        .thenReturn(Optional.of(existing));

    processor.process(event);

    ArgumentCaptor<SubscriptionEntity> subCap = ArgumentCaptor.forClass(SubscriptionEntity.class);
    verify(subscriptionRepository).save(subCap.capture());
    // Same row (same id reference) gets updated — plan changed to MAX, status to active.
    assertThat(subCap.getValue()).isSameAs(existing);
    assertThat(subCap.getValue().getPlan()).isEqualTo(Plan.MAX);
    assertThat(subCap.getValue().getStatus()).isEqualTo("active");
  }

  @Test
  void subscriptionUpdated_nonActiveStatus_leavesUserOnFree() {
    Subscription sub = activeSubscription("sub_1", "cus_123", "price_max_year", "past_due");
    Event event = eventWithPayload("customer.subscription.updated", "evt_sub_2", sub);

    when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByStripeSubscriptionId("sub_1"))
        .thenReturn(Optional.empty());

    processor.process(event);

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.FREE);
    assertThat(userCap.getValue().getPlanStatus()).isEqualTo("past_due");
  }

  @Test
  void subscriptionDeleted_downgradesToFree() {
    user.setPlan(Plan.PREMIUM);
    Subscription sub = activeSubscription("sub_1", "cus_123", "price_premium_month", "canceled");
    Event event = eventWithPayload("customer.subscription.deleted", "evt_del", sub);

    when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByStripeSubscriptionId("sub_1"))
        .thenReturn(Optional.empty());

    processor.process(event);

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.FREE);
    assertThat(userCap.getValue().getPlanStatus()).isEqualTo("canceled");
    assertThat(userCap.getValue().getPlanValidUntil()).isNull();
  }

  @Test
  void invoicePaymentFailed_setsPlanStatusPaymentFailed() {
    user.setPlan(Plan.PREMIUM);
    user.setPlanStatus("active");

    Invoice invoice = new Invoice();
    invoice.setCustomer("cus_123");
    Event event = eventWithPayload("invoice.payment_failed", "evt_pf", invoice);

    when(userRepository.findByStripeCustomerId("cus_123")).thenReturn(Optional.of(user));

    processor.process(event);

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlanStatus()).isEqualTo("PAYMENT_FAILED");
    // Plan itself is intentionally left untouched here.
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.PREMIUM);
  }

  @Test
  void unknownEventType_isMarkedProcessedWithoutDispatch() {
    Event event = eventOfType("payment_intent.succeeded", "evt_ignore");

    assertThat(processor.process(event)).isTrue();

    // saveAndFlush claims the id; save stamps processed_at.
    verify(stripeEventRepository).saveAndFlush(any(StripeEventEntity.class));
    verify(stripeEventRepository).save(any(StripeEventEntity.class));
    verify(userRepository, never()).save(any());
    verify(subscriptionRepository, never()).save(any());
  }

  // ---- helpers ---------------------------------------------------------

  private static Event eventOfType(String type, String id) {
    Event event = new Event();
    event.setId(id);
    event.setType(type);
    return event;
  }

  /**
   * Builds a Stripe {@link Event} whose data-object deserializer returns the given StripeObject.
   * We can't populate Event.data directly without parsing JSON, so we hand the processor a mocked
   * {@link EventDataObjectDeserializer} — the processor only ever goes through
   * {@link Event#getDataObjectDeserializer()}.
   */
  private static Event eventWithPayload(String type, String id, StripeObject payload) {
    EventDataObjectDeserializer deserializer =
        org.mockito.Mockito.mock(EventDataObjectDeserializer.class);
    when(deserializer.getObject()).thenReturn(Optional.ofNullable(payload));
    Event event =
        new Event() {
          @Override
          public EventDataObjectDeserializer getDataObjectDeserializer() {
            return deserializer;
          }
        };
    event.setId(id);
    event.setType(type);
    return event;
  }

  private static Subscription activeSubscription(
      String id, String customer, String priceId, String status) {
    return activeSubscription(id, customer, priceId, status, null, null);
  }

  private static Subscription activeSubscription(
      String id,
      String customer,
      String priceId,
      String status,
      Long periodStartEpochSeconds,
      Long periodEndEpochSeconds) {
    Subscription sub = new Subscription();
    sub.setId(id);
    sub.setCustomer(customer);
    sub.setStatus(status);
    sub.setCancelAtPeriodEnd(false);

    Price price = new Price();
    price.setId(priceId);
    SubscriptionItem item = new SubscriptionItem();
    item.setPrice(price);
    if (periodStartEpochSeconds != null) {
      item.setCurrentPeriodStart(periodStartEpochSeconds);
    }
    if (periodEndEpochSeconds != null) {
      item.setCurrentPeriodEnd(periodEndEpochSeconds);
    }
    SubscriptionItemCollection items = new SubscriptionItemCollection();
    items.setData(List.of(item));
    sub.setItems(items);
    return sub;
  }

  private static void setId(User user, Long id) {
    try {
      Field field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }
}
