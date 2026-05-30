package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolCallLogRecorder {

  private final ToolCallLogRepository toolCallLogRepository;
  private final ChatProperties chatProperties;

  void recordAssistantToolCalls(
      MessageEntity assistantMessage,
      ToolExecutionCollector toolExecutionCollector,
      String assistantResponse) {
    if (assistantMessage.getId() == null || toolExecutionCollector.isEmpty()) {
      return;
    }
    List<ToolCallLogEntity> logs =
        toolExecutionCollector.snapshot().stream()
            .map(record -> toEntity(assistantMessage.getId(), record, assistantResponse))
            .toList();
    toolCallLogRepository.saveAll(logs);
  }

  String extractContextExcerpt(String toolOutput, String assistantResponse) {
    if (toolOutput == null
        || toolOutput.isBlank()
        || assistantResponse == null
        || assistantResponse.isBlank()) {
      return null;
    }
    String normalizedAssistant = normalizeForMatch(assistantResponse);
    if (normalizedAssistant.isEmpty()) {
      return null;
    }
    List<String> matches = new ArrayList<>();
    for (String line : toolOutput.split("\\R+")) {
      String candidate = line.replaceFirst("^\\d+\\.\\s*", "").trim();
      if (candidate.length() < chatProperties.getMinContextExcerptCandidateLength()) {
        continue;
      }
      String normalizedCandidate = normalizeForMatch(candidate);
      if (normalizedCandidate.length() < chatProperties.getMinContextExcerptCandidateLength()) {
        continue;
      }
      if (normalizedAssistant.contains(normalizedCandidate) && !matches.contains(candidate)) {
        matches.add(candidate);
      }
      if (matches.size() >= chatProperties.getMaxContextExcerptMatches()) {
        break;
      }
    }
    if (matches.isEmpty()) {
      return null;
    }
    String excerpt = String.join("\n", matches);
    return excerpt.length() > chatProperties.getMaxContextExcerptLength()
        ? excerpt.substring(0, chatProperties.getMaxContextExcerptLength())
        : excerpt;
  }

  private ToolCallLogEntity toEntity(
      Long messageId, ToolExecutionCollector.Record record, String assistantResponse) {
    ToolCallLogEntity entity = new ToolCallLogEntity();
    entity.setMessageId(messageId);
    entity.setToolName(record.toolName());
    entity.setInputPayload(record.inputPayload());
    entity.setOutputPayload(record.outputPayload());
    entity.setStartedAt(record.startedAt());
    entity.setDurationMs(record.durationMs());
    entity.setContextExcerpt(extractContextExcerpt(record.outputPayload(), assistantResponse));
    return entity;
  }

  private String normalizeForMatch(String value) {
    return value == null
        ? ""
        : value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{Alnum}]+", " ")
            .trim();
  }
}
