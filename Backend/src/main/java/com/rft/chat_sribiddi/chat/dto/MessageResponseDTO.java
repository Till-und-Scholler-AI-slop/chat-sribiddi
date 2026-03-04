package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import java.time.Instant;
import java.util.UUID;

public record MessageResponseDTO(
    Long id, UUID chatId, String message, Role role, Instant createdAt) {

  public static MessageResponseDTO from(MessageEntity entity) {
    return new MessageResponseDTO(
        entity.getId(),
        entity.getChatId(),
        entity.getMessage(),
        entity.getRole(),
        entity.getCreatedAt());
  }
}
