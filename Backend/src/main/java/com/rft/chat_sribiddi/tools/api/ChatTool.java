package com.rft.chat_sribiddi.tools.api;

import java.util.Map;

/**
 * Generic interface for extensible chat tools.
 * Each tool has a unique name, description, and can be invoked with parameters.
 */
public interface ChatTool {

  /** Unique identifier for this tool (e.g. "calculator", "url-fetcher"). */
  String getName();

  /** Human-readable description shown to the user. */
  String getDisplayName();

  /** Description for the AI model explaining when to use this tool. */
  default String getDescription() {
    return SpringAiToolMetadata.description(this).orElseGet(this::getDisplayName);
  }

  /** Whether this tool requires per-user configuration (e.g. API key). */
  default boolean requiresUserConfig() {
    return false;
  }

  /** Risk classification used by the chat consent gate. */
  default ToolRiskLevel riskLevel() {
    return ToolRiskLevel.LOW;
  }

  /** Whether this tool must receive explicit per-request consent before model wiring. */
  default boolean requiresPerCallConsent() {
    return riskLevel() == ToolRiskLevel.HIGH;
  }

  /**
   * Execute the tool with the given parameters.
   *
   * @param userId the user invoking the tool (for config lookup)
   * @param parameters input parameters from the AI model
   * @return the tool result
   */
  ToolResult execute(String userId, Map<String, Object> parameters);
}
