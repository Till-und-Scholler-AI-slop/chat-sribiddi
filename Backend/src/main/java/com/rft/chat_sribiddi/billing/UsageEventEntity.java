package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-call usage/cost event, mirrored from the {@code usage_events} table created by Flyway
 * migration {@code V7__billing_and_plans.sql}. Persistence is the responsibility of BILL-6; this
 * entity exists so BILL-11's {@code GET /billing/usage} endpoint can aggregate the table via a
 * JPA repository using the existing app persistence context.
 */
@Entity
@Table(name = "usage_events")
@Getter
@Setter
public class UsageEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "message_id")
  private Long messageId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Provider provider;

  @Column(name = "model_id", nullable = false)
  private String modelId;

  @Column(name = "prompt_tokens")
  private Long promptTokens;

  @Column(name = "completion_tokens")
  private Long completionTokens;

  @Column(name = "total_tokens")
  private Long totalTokens;

  @Column(name = "cost_micro_cents")
  private Long costMicroCents;

  @Enumerated(EnumType.STRING)
  @Column(name = "billing_mode")
  private BillingMode billingMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "tier_at_call")
  private Plan tierAtCall;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
