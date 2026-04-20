package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
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

@ExtendWith(MockitoExtension.class)
class PlanLifecycleJobTest {

  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private UserRepository userRepository;

  private PlanLifecycleJob job;

  @BeforeEach
  void setUp() {
    job = new PlanLifecycleJob(subscriptionRepository, userRepository);
  }

  @Test
  void processExpiredSubscriptions_downgradesUserAndMarksSubscriptionExpired() {
    SubscriptionEntity subscription = new SubscriptionEntity();
    setId(subscription, 11L);
    subscription.setUserId(42L);
    subscription.setStatus("active");
    subscription.setPlan(Plan.PREMIUM);
    subscription.setCurrentPeriodEnd(Instant.now().minusSeconds(3600));

    User user = new User();
    setUserId(user, 42L);
    user.setPlan(Plan.PREMIUM);
    user.setPlanStatus("active");
    user.setPlanValidUntil(Instant.now().minusSeconds(3600));

    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of(subscription));
    when(userRepository.findById(42L)).thenReturn(Optional.of(user));

    job.processExpiredSubscriptions();

    ArgumentCaptor<SubscriptionEntity> subCap = ArgumentCaptor.forClass(SubscriptionEntity.class);
    verify(subscriptionRepository).save(subCap.capture());
    assertThat(subCap.getValue().getStatus()).isEqualTo(PlanLifecycleJob.EXPIRED_STATUS);

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.FREE);
    assertThat(userCap.getValue().getPlanStatus()).isEqualTo(PlanLifecycleJob.USER_EXPIRED_STATUS);
  }

  @Test
  void processExpiredSubscriptions_noResults_writesNothing() {
    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of());

    job.processExpiredSubscriptions();

    verify(subscriptionRepository, never()).save(any());
    verify(userRepository, never()).save(any());
  }

  @Test
  void processExpiredSubscriptions_missingUser_stillMarksSubscriptionExpired() {
    SubscriptionEntity subscription = new SubscriptionEntity();
    setId(subscription, 12L);
    subscription.setUserId(99L);
    subscription.setStatus("active");

    when(subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(eq("active"), any()))
        .thenReturn(List.of(subscription));
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    job.processExpiredSubscriptions();

    ArgumentCaptor<SubscriptionEntity> subCap = ArgumentCaptor.forClass(SubscriptionEntity.class);
    verify(subscriptionRepository).save(subCap.capture());
    assertThat(subCap.getValue().getStatus()).isEqualTo(PlanLifecycleJob.EXPIRED_STATUS);
    verify(userRepository, never()).save(any());
  }

  @Test
  void processPaymentFailedGracePeriod_downgradesUsersPastCutoff() {
    User user = new User();
    setUserId(user, 7L);
    user.setPlan(Plan.PREMIUM);
    user.setPlanStatus(StripeWebhookProcessor.PAYMENT_FAILED_STATUS);
    user.setPlanValidUntil(Instant.now().minus(java.time.Duration.ofDays(4)));

    when(userRepository.findByPlanStatusAndPlanValidUntilBefore(
            eq(StripeWebhookProcessor.PAYMENT_FAILED_STATUS), any()))
        .thenReturn(List.of(user));

    job.processPaymentFailedGracePeriod();

    ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCap.capture());
    assertThat(userCap.getValue().getPlan()).isEqualTo(Plan.FREE);
    assertThat(userCap.getValue().getPlanStatus())
        .isEqualTo(PlanLifecycleJob.USER_DOWNGRADED_STATUS);
  }

  @Test
  void processPaymentFailedGracePeriod_noResults_writesNothing() {
    when(userRepository.findByPlanStatusAndPlanValidUntilBefore(
            eq(StripeWebhookProcessor.PAYMENT_FAILED_STATUS), any()))
        .thenReturn(List.of());

    job.processPaymentFailedGracePeriod();

    verify(userRepository, never()).save(any());
  }

  @Test
  void processPaymentFailedGracePeriod_usesThreeDayCutoff() {
    when(userRepository.findByPlanStatusAndPlanValidUntilBefore(
            eq(StripeWebhookProcessor.PAYMENT_FAILED_STATUS), any()))
        .thenReturn(List.of());

    Instant before = Instant.now().minus(java.time.Duration.ofDays(3));
    job.processPaymentFailedGracePeriod();
    Instant after = Instant.now().minus(java.time.Duration.ofDays(3));

    ArgumentCaptor<Instant> cutoffCap = ArgumentCaptor.forClass(Instant.class);
    verify(userRepository)
        .findByPlanStatusAndPlanValidUntilBefore(
            eq(StripeWebhookProcessor.PAYMENT_FAILED_STATUS), cutoffCap.capture());
    Instant cutoff = cutoffCap.getValue();
    assertThat(cutoff).isBetween(before, after);
  }

  private static void setId(SubscriptionEntity entity, Long id) {
    try {
      Field field = SubscriptionEntity.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static void setUserId(User user, Long id) {
    try {
      Field field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }
}
