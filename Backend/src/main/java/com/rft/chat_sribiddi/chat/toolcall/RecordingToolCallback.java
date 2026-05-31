package com.rft.chat_sribiddi.chat.toolcall;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class RecordingToolCallback implements ToolCallback {

  private static final JsonMapper MAPPER = new JsonMapper();
  private static final TypeReference<Map<String, Object>> INPUT_TYPE = new TypeReference<>() {};

  private final ToolCallback delegate;
  private final String recordedToolName;
  private final ToolExecutionCollector collector;

  public RecordingToolCallback(
      ToolCallback delegate, String recordedToolName, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.recordedToolName = recordedToolName;
    this.collector = collector;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return record(toolInput, () -> delegate.call(toolInput));
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return record(toolInput, () -> delegate.call(toolInput, toolContext));
  }

  private String record(String toolInput, Supplier<String> invocation) {
    return collector.record(recordedToolName, inputPayload(toolInput), invocation);
  }

  private Map<String, Object> inputPayload(String toolInput) {
    if (toolInput == null || toolInput.isBlank()) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(toolInput, INPUT_TYPE);
    } catch (Exception ignored) {
      return Map.of("input", toolInput);
    }
  }
}
