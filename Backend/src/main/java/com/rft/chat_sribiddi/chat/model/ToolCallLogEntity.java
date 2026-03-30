package com.rft.chat_sribiddi.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tool_call_logs")
@Getter
@Setter
public class ToolCallLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long messageId;

  @Column(nullable = false, length = 255)
  private String toolName;

  @Column(columnDefinition = "TEXT", nullable = false)
  private String inputPayload;

  @Column(columnDefinition = "TEXT")
  private String outputPayload;

  @Column(nullable = false)
  private Instant startedAt;

  @Column(nullable = false)
  private Long durationMs;

  @Column(columnDefinition = "TEXT")
  private String contextExcerpt;

  @Column(nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
