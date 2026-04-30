package com.rft.chat_sribiddi.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Stripe webhook event record, mirrored from the {@code stripe_events} table created by Flyway
 * migration {@code V7__billing_and_plans.sql}. Used by {@link StripeWebhookProcessor} to guarantee
 * idempotent processing: the UNIQUE constraint on {@link #stripeEventId} prevents the same Stripe
 * event being processed twice, even if Stripe retries delivery.
 */
@Entity
@Table(name = "stripe_events")
@Getter
@Setter
public class StripeEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stripe_event_id", nullable = false, unique = true)
  private String stripeEventId;

  @Column(nullable = false)
  private String type;

  /**
   * JSON representation of the Stripe event as persisted by the application. Stored in a JSONB
   * column for easy inspection during incident response; this is typically a serialised
   * {@link com.stripe.model.Event} via {@link com.stripe.model.Event#toJson()} rather than the
   * original raw webhook request body delivered by Stripe, and the processor itself only reads
   * the deserialised event object.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  @PrePersist
  void onCreate() {
    if (receivedAt == null) {
      receivedAt = Instant.now();
    }
  }
}
