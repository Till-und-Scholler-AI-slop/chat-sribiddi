package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 *       passed — the user is downgraded to {@link Plan#FREE} with {@code planStatus=EXPIRED} and
 *       the subscription row is marked {@code expired}.</li>
 *   <li>Users whose {@code planStatus} is {@link StripeWebhookProcessor#PAYMENT_FAILED_STATUS}
 *       and whose {@code planValidUntil} is more than {@link #GRACE_PERIOD} in the past — the
 *       user is downgraded to {@link Plan#FREE} with {@code planStatus=DOWNGRADED}.</li>
 * </ul>
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

  /**
   * Finds subscriptions still marked {@code active} whose {@code currentPeriodEnd} has already
   * passed and downgrades their users to {@link Plan#FREE}.
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
    log.info("PlanLifecycleJob: found {} expired active subscription(s)", expired.size());
    for (SubscriptionEntity subscription : expired) {
      subscription.setStatus(EXPIRED_STATUS);
      subscriptionRepository.save(subscription);

      userRepository
          .findById(subscription.getUserId())
          .ifPresent(
              user -> {
                user.setPlan(Plan.FREE);
                user.setPlanStatus(USER_EXPIRED_STATUS);
                userRepository.save(user);
                log.info(
                    "PlanLifecycleJob: expired subscription id={} -> user id={} downgraded to FREE",
                    subscription.getId(),
                    user.getId());
              });
    }
  }

  /**
   * Finds users whose payment-failed grace period has elapsed and forces them onto
   * {@link Plan#FREE}.
   */
  @Scheduled(cron = "0 0 * * * *")
  @Transactional
  public void processPaymentFailedGracePeriod() {
    Instant cutoff = Instant.now().minus(GRACE_PERIOD);
    List<User> users =
        userRepository.findByPlanStatusAndPlanValidUntilBefore(
            StripeWebhookProcessor.PAYMENT_FAILED_STATUS, cutoff);
    if (users.isEmpty()) {
      return;
    }
    log.info(
        "PlanLifecycleJob: found {} user(s) past payment-failed grace period", users.size());
    for (User user : users) {
      user.setPlan(Plan.FREE);
      user.setPlanStatus(USER_DOWNGRADED_STATUS);
      userRepository.save(user);
      log.info(
          "PlanLifecycleJob: user id={} past grace period -> downgraded to FREE",
          user.getId());
    }
  }
}
