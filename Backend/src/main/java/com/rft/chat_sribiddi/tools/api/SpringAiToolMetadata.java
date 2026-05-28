package com.rft.chat_sribiddi.tools.api;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.ai.support.ToolCallbacks;

public final class SpringAiToolMetadata {

  private SpringAiToolMetadata() {}

  public static Optional<String> description(Object toolObject) {
    if (toolObject == null) {
      return Optional.empty();
    }
    try {
      return Arrays.stream(ToolCallbacks.from(toolObject))
          .map(callback -> callback.getToolDefinition().description())
          .filter(description -> description != null && !description.isBlank())
          .findFirst();
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }
}
