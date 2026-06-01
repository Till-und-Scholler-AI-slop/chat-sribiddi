package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import com.rft.chat_sribiddi.chat.toolcall.ToolCallStatus;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolCallLogRecorderTest {

  @Mock private ToolCallLogRepository toolCallLogRepository;

  private ToolCallLogRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new ToolCallLogRecorder(toolCallLogRepository, new ChatProperties());
  }

  @Test
  void recordAssistantToolCalls_persistsExecutionRecordWithContextExcerpt() {
    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setId(42L);
    ToolExecutionCollector collector = new ToolExecutionCollector();
    collector.record(
        "BraveSearchTool.searchWeb",
        Map.of("query", "weather"),
        () -> "1. Berlin weather is sunny today.\n2. Hamburg forecast says rain.");

    recorder.recordAssistantToolCalls(
        assistantMessage,
        collector,
        "The latest result says Berlin weather is sunny today.");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ToolCallLogEntity>> logsCaptor = ArgumentCaptor.forClass(List.class);
    verify(toolCallLogRepository).saveAll(logsCaptor.capture());

    ToolCallLogEntity log = logsCaptor.getValue().getFirst();
    assertThat(log.getMessageId()).isEqualTo(42L);
    assertThat(log.getToolName()).isEqualTo("BraveSearchTool.searchWeb");
    assertThat(log.getClientId()).isNotBlank();
    assertThat(log.getStatus()).isEqualTo(ToolCallStatus.SUCCESS);
    assertThat(log.getInputPayload()).contains("\"query\":\"weather\"");
    assertThat(log.getContextExcerpt()).isEqualTo("Berlin weather is sunny today.");
  }

  @Test
  void recordAssistantToolCalls_skipsWhenAssistantMessageHasNoId() {
    MessageEntity assistantMessage = new MessageEntity();
    ToolExecutionCollector collector = new ToolExecutionCollector();
    collector.record("Tool.call", Map.of(), () -> "tool output");

    recorder.recordAssistantToolCalls(assistantMessage, collector, "assistant response");

    verify(toolCallLogRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void extractContextExcerpt_returnsNullWhenToolOutputIsNotReferenced() {
    assertThat(recorder.extractContextExcerpt("1. Unused search result", "Different answer"))
        .isNull();
  }
}
