package com.rft.chat_sribiddi.tools.credentials;

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
    name = "tool_credentials",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "provider"})})
@Getter
@Setter
public class ToolCredentialEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private ToolCredentialProvider provider;

  @Convert(converter = ApiKeyAttributeConverter.class)
  @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
  private String secret;

  @Column(name = "key_version", nullable = false)
  private Integer keyVersion;

  private Instant createdAt;
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (keyVersion == null) {
      keyVersion = 1;
    }
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
