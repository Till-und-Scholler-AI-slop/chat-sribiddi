package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import java.time.Instant;
import java.util.UUID;

public record ChatResponseDTO(UUID chatId, String title, Instant createdAt, String userId) {

  public static ChatResponseDTO from(ChatEntity entity) {
    return new ChatResponseDTO(
        entity.getChatId(), entity.getTitle(), entity.getCreatedAt(), entity.getUserId());
  }
}
