package com.rft.chat_sribiddi.connectors;

import com.rft.chat_sribiddi.keyring.ApiKeyAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "connector_accounts",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "provider"})})
@Getter
@Setter
public class ConnectorAccountEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private ConnectorProvider provider;

  @Convert(converter = ApiKeyAttributeConverter.class)
  @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
  private String refreshToken;

  @Column(name = "granted_scopes", nullable = false, columnDefinition = "TEXT")
  private String grantedScopes = "";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ConnectorStatus status = ConnectorStatus.CONNECTED;

  @Column(name = "token_expires_at")
  private Instant tokenExpiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  private Instant createdAt;
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (status == null) {
      status = ConnectorStatus.CONNECTED;
    }
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
