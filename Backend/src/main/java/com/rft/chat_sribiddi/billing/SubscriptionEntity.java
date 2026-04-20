package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Stripe subscription record, mirrored from the {@code subscriptions} table created by Flyway
 * migration {@code V7__billing_and_plans.sql}. Populated/updated by the BILL-10 webhook handler;
 * read here (BILL-11) to return {@code periodEnd} / {@code cancelAtPeriodEnd} via
 * {@code GET /billing/me}.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
public class SubscriptionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "stripe_subscription_id", nullable = false, unique = true)
  private String stripeSubscriptionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Plan plan;

  @Column(nullable = false)
  private String status;

  @Column(name = "current_period_start")
  private Instant currentPeriodStart;

  @Column(name = "current_period_end")
  private Instant currentPeriodEnd;

  @Column(name = "cancel_at_period_end", nullable = false)
  private boolean cancelAtPeriodEnd;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
