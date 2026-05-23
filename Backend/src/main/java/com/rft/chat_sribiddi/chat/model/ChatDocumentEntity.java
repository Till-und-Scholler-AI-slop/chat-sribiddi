package com.rft.chat_sribiddi.chat.model;

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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "chat_documents")
@Getter
@Setter
public class ChatDocumentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "chat_id", nullable = false)
  private UUID chatId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(nullable = false)
  private String filename;

  @Column(name = "mime_type", nullable = false)
  private String mimeType;

  @Column(name = "byte_size", nullable = false)
  private Long byteSize;

  @Column(nullable = false)
  private String sha256;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DocumentStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public enum DocumentStatus {
    READY
  }
}
