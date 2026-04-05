package com.rft.chat_sribiddi.chat.dto;

import java.time.Instant;

public record ChatToolResponseDTO(
    Long id,
    String toolName,
    boolean enabled,
    Instant createdAt) {

  public static ChatToolResponseDTO from(com.rft.chat_sribiddi.chat.model.ChatToolEntity entity) {
    return new ChatToolResponseDTO(
        entity.getId(),
        entity.getToolName(),
        entity.isEnabled(),
        entity.getCreatedAt());
  }
}
