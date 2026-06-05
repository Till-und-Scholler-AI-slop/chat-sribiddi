package com.rft.chat_sribiddi.providerconnection;

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
    name = "provider_connections",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"user_id", "provider_id", "auth_method"})
    })
@Getter
@Setter
public class ProviderConnectionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "provider_id", nullable = false, length = 80)
  private String providerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_method", nullable = false, length = 64)
  private ProviderAuthMethod authMethod;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private ProviderConnectionStatus status;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "provider_account_id")
  private String providerAccountId;

  @Column(name = "provider_account_label")
  private String providerAccountLabel;

  @Column(columnDefinition = "TEXT")
  private String scopes;

  @Convert(converter = ApiKeyAttributeConverter.class)
  @Column(name = "encrypted_secret_json", columnDefinition = "TEXT")
  private String secretJson;

  @Column(name = "token_expires_at")
  private Instant tokenExpiresAt;

  @Column(name = "key_version", nullable = false)
  private Integer keyVersion;

  @Column(name = "default_connection", nullable = false)
  private Boolean defaultConnection;

  @Column(name = "last_validated_at")
  private Instant lastValidatedAt;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
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
    if (status == null) {
      status = ProviderConnectionStatus.READY;
    }
    if (keyVersion == null) {
      keyVersion = 1;
    }
    if (defaultConnection == null) {
      defaultConnection = false;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
