package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity that stores a user's encrypted API key for a specific provider.
 *
 * <p>The combination of {@code userId} and {@code provider} is unique, allowing one key per user
 * and provider. Keys are stored encrypted at rest via {@link ApiKeyAttributeConverter}.
 */
@Entity
@Table(
    name = "api_keys",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "provider"})})
@Getter
@Setter
public class ApiKeyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Provider provider;

  @Convert(converter = ApiKeyAttributeConverter.class)
  @Column(name = "encrypted_key", nullable = false, columnDefinition = "TEXT")
  private String apiKey;

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
