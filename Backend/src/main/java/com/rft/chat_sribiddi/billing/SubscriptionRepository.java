package com.rft.chat_sribiddi.billing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, Long> {

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
