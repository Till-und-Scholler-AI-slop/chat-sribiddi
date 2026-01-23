package com.rft.chat_sribiddi.chat.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "chats")
@Getter
@Setter
public class ChatEntity {
  @Id @GeneratedValue private UUID chatId;

  private String title;

  private Instant createdAt;

  @Column(nullable = false)
  private String userId;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
