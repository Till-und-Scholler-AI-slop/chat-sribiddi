package com.rft.chat_sribiddi.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, Long> {

  /**
   * Returns subscriptions with the given {@code status} whose {@code currentPeriodEnd} is strictly
   * before {@code cutoff}. Used by {@link PlanLifecycleJob} to find subscriptions that are still
   * marked {@code active} but whose billing period has already elapsed, so the user can be
   * downgraded to {@link com.rft.chat_sribiddi.auth.Plan#FREE}.
   */
  List<SubscriptionEntity> findByStatusAndCurrentPeriodEndBefore(String status, Instant cutoff);

  /**
   * Conditionally flips a subscription's status from {@code active} to the given
   * {@code newStatus}, but only when the row is still {@code active} and its
   * {@code currentPeriodEnd} is still {@code < cutoff} at UPDATE time. This re-check protects
   * against lost updates when {@link StripeWebhookProcessor} renews or reactivates the same
   * subscription concurrently with {@link PlanLifecycleJob}. Returns the number of rows updated
   * (0 when the row was changed between the initial read and this UPDATE).
   */
  @Modifying
  @Query(
      "UPDATE SubscriptionEntity s SET s.status = :newStatus, s.updatedAt = :now "
          + "WHERE s.id = :id AND s.status = 'active' AND s.currentPeriodEnd < :cutoff")
  int markExpiredIfStillActive(
      @Param("id") Long id,
      @Param("newStatus") String newStatus,
      @Param("cutoff") Instant cutoff,
      @Param("now") Instant now);

  /**
   * Returns the most recently updated subscription for the given internal user id, independent of
   * status. Callers typically inspect {@code status} and {@code currentPeriodEnd} themselves to
   * decide whether the subscription is active.
   */
  Optional<SubscriptionEntity> findFirstByUserIdOrderByUpdatedAtDesc(Long userId);

  /**
   * Looks up a subscription by its Stripe subscription id ({@code sub_…}). Used by
   * {@link StripeWebhookProcessor} to upsert the exact row an event refers to, rather than
   * relying on "most recent subscription for user" which breaks when a user has multiple
   * historical subscriptions.
   */
  Optional<SubscriptionEntity> findByStripeSubscriptionId(String stripeSubscriptionId);
}

