package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import java.time.Instant;
import java.util.UUID;

public record MessageResponseDTO(
    Long id, UUID chatId, String message, String context, Role role, Instant createdAt,
    Long promptTokens, Long generationTokens, Long totalTokens, boolean hasToolCalls) {

  public static MessageResponseDTO from(MessageEntity entity) {
    return from(entity, false);
  }

  public static MessageResponseDTO from(MessageEntity entity, boolean hasToolCalls) {
    return new MessageResponseDTO(
        entity.getId(),
        entity.getChatId(),
        entity.getMessage(),
        entity.getContext(),
        entity.getRole(),
        entity.getCreatedAt(),
        entity.getPromptTokens(),
        entity.getGenerationTokens(),
        entity.getTotalTokens(),
        hasToolCalls);
  }
}
