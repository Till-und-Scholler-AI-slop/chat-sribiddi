package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import java.time.Instant;

public record ToolCallLogResponseDTO(
    Long id,
    Long messageId,
    String toolName,
    String inputPayload,
    String outputPayload,
    Instant startedAt,
    Long durationMs,
    String contextExcerpt) {

  public static ToolCallLogResponseDTO from(ToolCallLogEntity entity) {
    return new ToolCallLogResponseDTO(
        entity.getId(),
        entity.getMessageId(),
        entity.getToolName(),
        entity.getInputPayload(),
        entity.getOutputPayload(),
        entity.getStartedAt(),
        entity.getDurationMs(),
        entity.getContextExcerpt());
  }
}
