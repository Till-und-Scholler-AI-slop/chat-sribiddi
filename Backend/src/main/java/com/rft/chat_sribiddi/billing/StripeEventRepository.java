package com.rft.chat_sribiddi.billing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link StripeEventEntity}. The primary use case is idempotency: before
 * {@link StripeWebhookProcessor} acts on an incoming webhook, it checks whether the Stripe event
 * id has already been recorded.
 */
@Repository
public interface StripeEventRepository extends JpaRepository<StripeEventEntity, Long> {

  Optional<StripeEventEntity> findByStripeEventId(String stripeEventId);
}
