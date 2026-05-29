package com.rft.chat_sribiddi.chat.toolcall;

import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

public final class ToolConsentContext {

  public static final String KEY = "chat-sribiddi.toolConsentArgs";

  private ToolConsentContext() {}

  public static Map<String, Object> argsFor(ToolContext toolContext, String toolName) {
    if (toolContext == null || toolName == null || toolName.isBlank()) {
      return Map.of();
    }
    Object consentContext = toolContext.getContext().get(KEY);
    if (!(consentContext instanceof Map<?, ?> consents)) {
      return Map.of();
    }
    Object args = consents.get(toolName);
    if (!(args instanceof Map<?, ?> rawArgs)) {
      return Map.of();
    }
    return rawArgs.entrySet().stream()
        .filter(entry -> entry.getKey() instanceof String)
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                entry -> (String) entry.getKey(), Map.Entry::getValue, (first, second) -> second));
  }
}
