package com.rft.chat_sribiddi.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(unique = true)
  private String email;

  @Column(unique = true, nullable = false)
  private String providerId;

  @Column(columnDefinition = "TEXT")
  private String customInstructions;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Plan plan = Plan.FREE;

  private String planStatus;

  private Instant planValidUntil;

  @Column(unique = true)
  private String stripeCustomerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BillingMode billingModePreference = BillingMode.AUTO;
}
