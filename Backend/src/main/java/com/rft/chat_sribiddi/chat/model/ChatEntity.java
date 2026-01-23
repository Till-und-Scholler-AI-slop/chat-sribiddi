package com.rft.chat_sribiddi.chat.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "chats")
@Getter
@Setter
public class ChatEntity {
  @Id
  @GeneratedValue
  private UUID chatId;

  private String title;

  private Instant createdAt;

  private String userId;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
