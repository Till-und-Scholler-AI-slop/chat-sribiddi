package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.toolcall.LoggedBraveSearchTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedCalculatorTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedCurrentDateTimeTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedPythonInterpreterTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedUrlFetcherTool;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptToolBinder {

  private final ChatProperties chatProperties;
  private final ChatToolRepository chatToolRepository;
  private final ApiKeyService apiKeyService;
  private final WebSearchProperties webSearchProperties;
  private final HttpClient webSearchHttpClient;
  private final ToolRegistry toolRegistry;
  private final CalculatorTool calculatorTool;
  private final UrlFetcherTool urlFetcherTool;
  private final PythonInterpreterTool pythonInterpreterTool;
  private final CurrentDateTimeTool currentDateTimeTool;
  private final EntitlementService entitlementService;

  /**
   * Binds the currently enabled chat tools to a Spring AI prompt request.
   *
   * <p>Most tools are registered once and gated by entitlements plus per-chat settings. Brave Search
   * is request-scoped because it needs the user's stored Brave API key.
   */
  void bindTools(
      UUID chatId,
      String userId,
      RequestDTO.Provider provider,
      String modelId,
      Boolean webSearchEnabled,
      List<RequestDTO.ToolConsent> toolConsents,
      ToolExecutionCollector collector,
      ChatClient.ChatClientRequestSpec promptSpec) {
    if (!supportsTools(provider, modelId)) {
      log.debug(
          "Skipping tool binding for provider={} model={} due to app.chat tool gating",
          provider,
          modelId);
      return;
    }
    for (ChatTool tool : toolRegistry.getAll()) {
      if (!isToolAllowedForUser(userId, tool.getName())) {
        continue;
      }
      if (!isToolEnabledForChat(chatId, tool.getName())) {
        continue;
      }
      if (tool.requiresPerCallConsent() && !hasToolConsent(tool, toolConsents)) {
        log.info(
            "Skipping high-risk tool '{}' because request did not include explicit consent",
            tool.getName());
        continue;
      }
      bindTool(chatId, userId, webSearchEnabled, collector, promptSpec, tool);
    }
  }

  boolean supportsTools(RequestDTO.Provider provider, String modelId) {
    if (provider == null || modelId == null || modelId.isBlank()) {
      return true;
    }
    String providerName = provider.name().toLowerCase(Locale.ROOT);
    String normalizedModel = modelId.toLowerCase(Locale.ROOT);
    return chatProperties.getToolDisabledModelPatterns().stream()
        .filter(pattern -> pattern != null && !pattern.isBlank())
        .noneMatch(pattern -> matchesProviderModelPattern(pattern, providerName, normalizedModel));
  }

  boolean hasToolConsent(ChatTool tool, List<RequestDTO.ToolConsent> toolConsents) {
    if (!tool.requiresPerCallConsent()) {
      return true;
    }
    if (toolConsents == null || toolConsents.isEmpty()) {
      return false;
    }
    return toolConsents.stream()
        .filter(Objects::nonNull)
        .anyMatch(
            consent ->
                tool.getName().equals(consent.toolName())
                    && tool.riskLevel().name().equalsIgnoreCase(consent.riskLevel()));
  }

  private void bindTool(
      UUID chatId,
      String userId,
      Boolean webSearchEnabled,
      ToolExecutionCollector collector,
      ChatClient.ChatClientRequestSpec promptSpec,
      ChatTool tool) {
    switch (tool.getName()) {
      case "current-datetime" ->
          promptSpec.tools(new LoggedCurrentDateTimeTool(currentDateTimeTool, collector));
      case "brave-search" -> {
        if (Boolean.TRUE.equals(webSearchEnabled)) {
          resolveWebSearchTool(userId)
              .map(t -> new LoggedBraveSearchTool(t, collector))
              .ifPresent(promptSpec::tools);
        }
      }
      case "calculator" -> promptSpec.tools(new LoggedCalculatorTool(calculatorTool, collector));
      case "url-fetcher" -> promptSpec.tools(new LoggedUrlFetcherTool(urlFetcherTool, collector));
      case "python-interpreter" ->
          promptSpec.tools(new LoggedPythonInterpreterTool(pythonInterpreterTool, collector));
      default -> promptSpec.tools(tool);
    }
  }

  private Optional<BraveSearchTool> resolveWebSearchTool(String userId) {
    return apiKeyService
        .getKey(userId, Provider.BRAVE_SEARCH)
        .map(key -> new BraveSearchTool(key, webSearchHttpClient, webSearchProperties));
  }

  private boolean matchesProviderModelPattern(
      String rawPattern, String providerName, String normalizedModel) {
    String pattern = rawPattern.trim().toLowerCase(Locale.ROOT);
    int separator = pattern.indexOf(':');
    if (separator <= 0 || separator == pattern.length() - 1) {
      log.warn("Ignoring invalid app.chat.tool-disabled-model-patterns entry '{}'", rawPattern);
      return false;
    }
    String providerPattern = pattern.substring(0, separator).trim();
    String modelPattern = pattern.substring(separator + 1).trim();
    return wildcardMatches(providerPattern, providerName)
        && wildcardMatches(modelPattern, normalizedModel);
  }

  private boolean wildcardMatches(String pattern, String value) {
    int patternIndex = 0;
    int valueIndex = 0;
    int starIndex = -1;
    int matchIndex = 0;

    while (valueIndex < value.length()) {
      if (patternIndex < pattern.length()
          && (pattern.charAt(patternIndex) == '?'
              || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
        patternIndex++;
        valueIndex++;
      } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
        starIndex = patternIndex++;
        matchIndex = valueIndex;
      } else if (starIndex != -1) {
        patternIndex = starIndex + 1;
        valueIndex = ++matchIndex;
      } else {
        return false;
      }
    }

    while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
      patternIndex++;
    }
    return patternIndex == pattern.length();
  }

  private boolean isToolAllowedForUser(String userId, String toolName) {
    try {
      Entitlements entitlements = entitlementService.get(userId);
      return entitlements != null && entitlements.allowsTool(toolName);
    } catch (RuntimeException ex) {
      log.debug("Unable to resolve tool entitlement user={} tool={}", userId, toolName, ex);
      return false;
    }
  }

  private boolean isToolEnabledForChat(UUID chatId, String toolName) {
    return chatToolRepository
        .findByChatIdAndToolName(chatId, toolName)
        .map(ChatToolEntity::isEnabled)
        .orElseGet(
            () ->
                switch (toolName) {
                  case "calculator", "current-datetime", "brave-search" -> true;
                  default -> false;
                });
  }
}
