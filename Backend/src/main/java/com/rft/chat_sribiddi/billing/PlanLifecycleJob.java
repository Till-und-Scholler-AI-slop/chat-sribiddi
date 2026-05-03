package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.audit.AuditLogService;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that keeps user plan state in sync with subscription lifecycle events
 * (BILL-13). Runs hourly and handles two cases that {@link StripeWebhookProcessor} cannot
 * cover on its own because they are time-based rather than event-driven:
 *
 * <ul>
 *   <li>Subscriptions still flagged {@code active} whose {@code currentPeriodEnd} has already
 *       passed — the user is downgraded to {@link com.rft.chat_sribiddi.auth.Plan#FREE} with
 *       {@code planStatus=EXPIRED} and the subscription row is marked {@code expired}.</li>
 *   <li>Users whose {@code planStatus} is {@link StripeWebhookProcessor#PAYMENT_FAILED_STATUS}
 *       and whose {@code planValidUntil} is more than {@link #GRACE_PERIOD} in the past — the
 *       user is downgraded to {@link com.rft.chat_sribiddi.auth.Plan#FREE} with
 *       {@code planStatus=DOWNGRADED}.</li>
 * </ul>
 *
 * <p>Both transitions use conditional UPDATE queries that re-check state at write time, so a
 * concurrent {@link StripeWebhookProcessor} webhook (e.g. a renewal or payment-recovery event)
 * cannot be overwritten by a stale read from this job.
 *
 * <p>Out of scope (per BILL-13): notification emails and Stripe API calls for cancellation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanLifecycleJob {

  /** Subscription.status value for subscriptions whose billing period has elapsed. */
  static final String EXPIRED_STATUS = "expired";

  /** User.planStatus value set when an active subscription has expired. */
  static final String USER_EXPIRED_STATUS = "EXPIRED";

  /** User.planStatus value set when a payment-failed grace period has elapsed. */
  static final String USER_DOWNGRADED_STATUS = "DOWNGRADED";

  /** Grace period granted to users in {@code PAYMENT_FAILED} state before forced downgrade. */
  static final Duration GRACE_PERIOD = Duration.ofDays(3);

  /** Subscription.status value that marks an active (billable) subscription. */
  private static final String ACTIVE_STATUS = "active";

  private final SubscriptionRepository subscriptionRepository;
  private final UserRepository userRepository;
  private final AuditLogService auditLogService;

  /**
   * Finds subscriptions still marked {@code active} whose {@code currentPeriodEnd} has already
   * passed and downgrades their users to {@link com.rft.chat_sribiddi.auth.Plan#FREE}.
   *
   * <p>Uses conditional UPDATEs so a concurrent subscription renewal from
   * {@link StripeWebhookProcessor} won't be silently overwritten.
   */
  @Scheduled(cron = "0 0 * * * *")
  @Transactional
  public void processExpiredSubscriptions() {
    Instant now = Instant.now();
    List<SubscriptionEntity> expired =
        subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(ACTIVE_STATUS, now);
    if (expired.isEmpty()) {
      return;
    }

    int subsExpired = 0;
    int usersDowngraded = 0;
    for (SubscriptionEntity subscription : expired) {
      int subUpdated =
          subscriptionRepository.markExpiredIfStillActive(
              subscription.getId(), EXPIRED_STATUS, now, now);
      if (subUpdated == 0) {
        log.debug(
            "PlanLifecycleJob: subscription id={} changed concurrently, skipping",
            subscription.getId());
        continue;
      }
      subsExpired++;

      int userUpdated =
          userRepository.downgradeToFreeIfExpired(
              subscription.getUserId(), USER_EXPIRED_STATUS, now);
      if (userUpdated > 0) {
        usersDowngraded++;
        auditLogService.recordPlanStateChange(
            subscription.getUserId(),
            subscription.getPlan(),
            Plan.FREE,
            subscription.getStatus(),
            USER_EXPIRED_STATUS,
            "plan_lifecycle_job",
            "subscription_expired",
            metadata(
                "subscription_id",
                subscription.getId(),
                "stripe_subscription_id",
                subscription.getStripeSubscriptionId(),
                "current_period_end",
                instant(subscription.getCurrentPeriodEnd()),
                "cutoff",
                instant(now)));
        log.debug(
            "PlanLifecycleJob: expired subscription id={} -> user id={} downgraded to FREE",
            subscription.getId(),
            subscription.getUserId());
      } else {
        log.debug(
            "PlanLifecycleJob: expired subscription id={} but user id={} not downgraded "
                + "(concurrent renewal or already FREE)",
            subscription.getId(),
            subscription.getUserId());
      }
    }
    log.info(
        "PlanLifecycleJob: expired {} subscription(s); downgraded {} user(s) to FREE",
        subsExpired,
        usersDowngraded);
  }

  /**
   * Finds users whose payment-failed grace period has elapsed and forces them onto
   * {@link com.rft.chat_sribiddi.auth.Plan#FREE}.
   *
   * <p>Uses a single bulk conditional UPDATE so a concurrent payment-recovery event from
   * {@link StripeWebhookProcessor} that clears {@code planStatus=PAYMENT_FAILED} won't be
   * overwritten.
   */
  @Scheduled(cron = "0 0 * * * *")
  @Transactional
  public void processPaymentFailedGracePeriod() {
    Instant cutoff = Instant.now().minus(GRACE_PERIOD);
    List<User> candidates =
        userRepository.findByPlanStatusAndPlanValidUntilBefore(
            StripeWebhookProcessor.PAYMENT_FAILED_STATUS, cutoff);
    int downgraded = 0;
    for (User user : candidates) {
      int userUpdated =
          userRepository.downgradePaymentFailedUserIfStillOverdue(
              user.getId(),
              StripeWebhookProcessor.PAYMENT_FAILED_STATUS,
              USER_DOWNGRADED_STATUS,
              cutoff);
      if (userUpdated > 0) {
        downgraded++;
        auditLogService.recordPlanStateChange(
            user.getId(),
            user.getPlan(),
            Plan.FREE,
            user.getPlanStatus(),
            USER_DOWNGRADED_STATUS,
            "plan_lifecycle_job",
            "payment_failed_grace_elapsed",
            metadata(
                "plan_valid_until",
                instant(user.getPlanValidUntil()),
                "cutoff",
                instant(cutoff)));
      }
    }
    if (downgraded > 0) {
      log.info(
          "PlanLifecycleJob: downgraded {} user(s) past payment-failed grace period",
          downgraded);
    }
  }

  private static Map<String, Object> metadata(Object... keyValues) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      Object key = keyValues[i];
      Object value = keyValues[i + 1];
      if (key instanceof String name && value != null) {
        metadata.put(name, value);
      }
    }
    return metadata;
  }

  private static String instant(Instant value) {
    return value == null ? null : value.toString();
  }
}
