package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.toolcall.ToolCallStatus;
import java.time.Instant;

public record ToolCallLogResponseDTO(
    Long id,
    Long messageId,
    String toolName,
    String clientId,
    ToolCallStatus status,
    String inputPayload,
    String outputPayload,
    String errorMessage,
    Instant startedAt,
    Long durationMs,
    String contextExcerpt,
    String displayType) {

  public static ToolCallLogResponseDTO from(ToolCallLogEntity entity) {
    return new ToolCallLogResponseDTO(
        entity.getId(),
        entity.getMessageId(),
        entity.getToolName(),
        entity.getClientId(),
        entity.getStatus(),
        entity.getInputPayload(),
        entity.getOutputPayload(),
        entity.getErrorMessage(),
        entity.getStartedAt(),
        entity.getDurationMs(),
        entity.getContextExcerpt(),
        entity.getDisplayType());
  }
}
