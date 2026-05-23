package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.ChatDocumentEntity;
import java.time.Instant;

public record ChatDocumentDTO(
    Long id,
    String filename,
    String mimeType,
    Long byteSize,
    String status,
    Instant createdAt) {

  public static ChatDocumentDTO from(ChatDocumentEntity entity) {
    return new ChatDocumentDTO(
        entity.getId(),
        entity.getFilename(),
        entity.getMimeType(),
        entity.getByteSize(),
        entity.getStatus().name(),
        entity.getCreatedAt());
  }
}
