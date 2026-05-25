package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.audit.AuditLogService;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanLifecycleJobTest {

  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuditLogService auditLogService;

  private PlanLifecycleJob job;

  @BeforeEach
  void setUp() {
    job = new PlanLifecycleJob(subscriptionRepository, userRepository, auditLogService);
  }

  @Test
  void processExpiredSubscriptions_conditionallyMarksExpiredAndDowngradesUser() {
    SubscriptionEntity subscription = newSubscription(11L, 42L);
    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of(subscription));
    when(subscriptionRepository.markExpiredIfStillActive(
            eq(11L), eq(PlanLifecycleJob.EXPIRED_STATUS), any(), any()))
        .thenReturn(1);
    when(userRepository.downgradeToFreeIfExpired(
            eq(42L), eq(PlanLifecycleJob.USER_EXPIRED_STATUS), any()))
        .thenReturn(1);

    job.processExpiredSubscriptions();

    verify(subscriptionRepository)
        .markExpiredIfStillActive(eq(11L), eq(PlanLifecycleJob.EXPIRED_STATUS), any(), any());
    verify(userRepository)
        .downgradeToFreeIfExpired(eq(42L), eq(PlanLifecycleJob.USER_EXPIRED_STATUS), any());
    verify(auditLogService)
        .recordPlanStateChange(
            eq(42L),
            eq(Plan.PREMIUM),
            eq(Plan.FREE),
            eq("active"),
            eq(PlanLifecycleJob.USER_EXPIRED_STATUS),
            eq("plan_lifecycle_job"),
            eq("subscription_expired"),
            any());
  }

  @Test
  void processExpiredSubscriptions_noResults_writesNothing() {
    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of());

    job.processExpiredSubscriptions();

    verify(subscriptionRepository, never())
        .markExpiredIfStillActive(any(), any(), any(), any());
    verify(userRepository, never()).downgradeToFreeIfExpired(any(), any(), any());
  }

  @Test
  void processExpiredSubscriptions_lostRace_skipsUserUpdate() {
    SubscriptionEntity subscription = newSubscription(12L, 99L);
    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of(subscription));
    // Subscription row was changed concurrently (e.g. webhook renewal) — conditional update
    // affects 0 rows, so the user must not be touched.
    when(subscriptionRepository.markExpiredIfStillActive(
            eq(12L), eq(PlanLifecycleJob.EXPIRED_STATUS), any(), any()))
        .thenReturn(0);

    job.processExpiredSubscriptions();

    verify(userRepository, never()).downgradeToFreeIfExpired(any(), any(), any());
  }

  @Test
  void processExpiredSubscriptions_subExpiredButUserRenewed_userNotOverwritten() {
    SubscriptionEntity subscription = newSubscription(13L, 55L);
    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of(subscription));
    when(subscriptionRepository.markExpiredIfStillActive(
            eq(13L), eq(PlanLifecycleJob.EXPIRED_STATUS), any(), any()))
        .thenReturn(1);
    // User conditional UPDATE matched 0 rows (plan already FREE / planValidUntil in the future).
    when(userRepository.downgradeToFreeIfExpired(
            eq(55L), eq(PlanLifecycleJob.USER_EXPIRED_STATUS), any()))
        .thenReturn(0);

    job.processExpiredSubscriptions();

    verify(userRepository)
        .downgradeToFreeIfExpired(eq(55L), eq(PlanLifecycleJob.USER_EXPIRED_STATUS), any());
  }

  /**
   * Small factory that relies on Lombok-generated setters (User/SubscriptionEntity are
   * annotated with {@code @Setter} at the class level) — no reflection required.
   */
  private static SubscriptionEntity newSubscription(Long id, Long userId) {
    SubscriptionEntity subscription = new SubscriptionEntity();
    subscription.setId(id);
    subscription.setUserId(userId);
    subscription.setStatus("active");
    subscription.setPlan(Plan.PREMIUM);
    subscription.setCurrentPeriodEnd(Instant.now().minusSeconds(3600));
    return subscription;
  }

  /** Kept for static analysis: {@link User} must expose a {@code setId(Long)} via Lombok. */
  @SuppressWarnings("unused")
  private static User newUser(Long id) {
    User user = new User();
    user.setId(id);
    return user;
  }
}
