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
 * and provider. Keys are stored encrypted at rest; see {@link CryptoService}.
 */
@Entity
@Table(
    name = "api_keys",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"userId", "provider"})})
@Getter
@Setter
public class ApiKeyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Provider provider;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String encryptedKey;

  private Instant createdAt;
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
