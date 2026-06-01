package com.rft.chat_sribiddi.chat.toolcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

class RecordingToolCallbackTest {

  @Test
  void call_delegatesToSpringAiCallbackAndRecordsJsonInput() {
    ToolExecutionCollector collector = new ToolExecutionCollector();
    RecordingToolCallback callback =
        new RecordingToolCallback(new EchoToolCallback(), "EchoTool.echo", collector);

    String output = callback.call("{\"value\":\"hello\"}");

    assertThat(output).isEqualTo("echo:{\"value\":\"hello\"}");
    assertThat(collector.snapshot())
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.toolName()).isEqualTo("EchoTool.echo");
              assertThat(record.clientId()).isNotBlank();
              assertThat(record.status()).isEqualTo(ToolCallStatus.SUCCESS);
              assertThat(record.inputPayload()).isEqualTo("{\"value\":\"hello\"}");
              assertThat(record.outputPayload()).isEqualTo("echo:{\"value\":\"hello\"}");
            });
  }

  @Test
  void call_emitsRunningAndCompletedEvents() {
    ToolExecutionCollector collector = new ToolExecutionCollector();
    java.util.List<ToolExecutionCollector.Event> events = new java.util.ArrayList<>();
    collector.setEventSink(events::add);
    RecordingToolCallback callback =
        new RecordingToolCallback(new EchoToolCallback(), "EchoTool.echo", collector, "generic");

    callback.call("{\"value\":\"hello\"}");

    assertThat(events).hasSize(2);
    assertThat(events.get(0).status()).isEqualTo(ToolCallStatus.RUNNING);
    assertThat(events.get(1).status()).isEqualTo(ToolCallStatus.SUCCESS);
    assertThat(events.get(1).displayType()).isEqualTo("generic");
  }

  @Test
  void call_recordsNonJsonInputAsFallbackPayload() {
    ToolExecutionCollector collector = new ToolExecutionCollector();
    RecordingToolCallback callback =
        new RecordingToolCallback(new EchoToolCallback(), "EchoTool.echo", collector);

    callback.call("plain input");

    assertThat(collector.snapshot())
        .singleElement()
        .satisfies(
            record -> assertThat(record.inputPayload()).isEqualTo("{\"input\":\"plain input\"}"));
  }

  private static final class EchoToolCallback implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      ToolDefinition definition = org.mockito.Mockito.mock(ToolDefinition.class);
      when(definition.name()).thenReturn("echo");
      return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
      return "echo:" + toolInput;
    }
  }
}
