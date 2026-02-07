package com.rft.chat_sribiddi.chat.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "messages")
@Getter
@Setter
public class MessageEntity {

  @Id @GeneratedValue private Long id;

  @Column(nullable = false)
  private UUID chatId;

  @Column(columnDefinition = "TEXT", nullable = false)
  private String message;

  @Column(columnDefinition = "TEXT")
  private String context;

  @Enumerated(EnumType.STRING)
  private Role role;

  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
