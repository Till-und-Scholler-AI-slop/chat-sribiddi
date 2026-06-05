package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.toolcall.RecordingToolCallback;
import com.rft.chat_sribiddi.chat.toolcall.ToolConsentContext;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptToolBinder {

  static final String TOOL_CONSENT_CONTEXT_KEY = ToolConsentContext.KEY;

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
  private final CredentialAccessService credentialAccessService;

  /**
   * Binds the currently enabled chat tools to a Spring AI prompt request.
   *
   * <p>Most tools are registered once and gated by access limits plus per-chat settings. Brave
   * Search is request-scoped because it needs the user's stored Brave API key.
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
    AccessLimits accessLimits = credentialAccessService.authenticatedLimits();

    List<ToolCallback> toolCallbacks = new ArrayList<>();
    Map<String, Boolean> enabledByToolName = null;
    for (ChatTool tool : toolRegistry.getAll()) {
      if (!isToolAllowed(tool, userId, accessLimits)) {
        continue;
      }
      if (enabledByToolName == null) {
        enabledByToolName = loadToolOverrides(chatId);
      }
      if (!isToolEnabledForChat(tool.getName(), enabledByToolName)) {
        continue;
      }
      if (tool.requiresPerCallConsent() && !hasToolConsent(tool, toolConsents)) {
        log.info(
            "Skipping high-risk tool '{}' because request did not include explicit consent",
            tool.getName());
        continue;
      }
      toolCallbacks.addAll(resolveToolCallbacks(userId, webSearchEnabled, collector, tool));
    }
    if (!toolCallbacks.isEmpty()) {
      Map<String, Object> toolContext = toolContext(toolConsents);
      promptSpec.tools(
          toolSpec -> {
            toolSpec.callbacks(toolCallbacks.toArray(ToolCallback[]::new));
            // Always attach the consent context, even when it is empty. Spring AI 2.0.0-M8's
            // MethodToolCallback rejects any tool that declares a ToolContext parameter (e.g.
            // UrlFetcherTool#fetchUrl) when the supplied ToolContext has an empty context map,
            // so a non-empty outer context map is required for those tools to run at all.
            toolSpec.context(TOOL_CONSENT_CONTEXT_KEY, toolContext);
            // Surface the active user id so tools that need per-user rate limiting (e.g.
            // UrlFetcherTool) can identify the caller without an extra plumbing layer.
            toolSpec.context(UrlFetcherTool.USER_ID_CONTEXT_KEY, userId);
          });
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

  private List<ToolCallback> resolveToolCallbacks(
      String userId,
      Boolean webSearchEnabled,
      ToolExecutionCollector collector,
      ChatTool tool) {
    return switch (tool.getName()) {
      case "current-datetime" ->
          toolCallbacks(currentDateTimeTool, collector);
      case "brave-search" -> {
        if (Boolean.TRUE.equals(webSearchEnabled)) {
          yield resolveWebSearchTool(userId)
              .map(t -> toolCallbacks(t, collector))
              .orElseGet(List::of);
        }
        yield List.of();
      }
      case "calculator" -> toolCallbacks(calculatorTool, collector);
      case "url-fetcher" -> toolCallbacks(urlFetcherTool, collector);
      case "python-interpreter" ->
          toolCallbacks(pythonInterpreterTool, collector);
      default -> toolCallbacks(tool, collector);
    };
  }

  private List<ToolCallback> toolCallbacks(Object toolObject, ToolExecutionCollector collector) {
    ToolCallback[] callbacks = ToolCallbacks.from(toolObject);
    if (callbacks.length == 0) {
      log.warn("Tool object {} did not expose any Spring AI ToolCallback", toolObject.getClass());
      return List.of();
    }
    return Arrays.stream(callbacks)
        .map(callback -> recordingCallback(toolObject, callback, collector))
        .toList();
  }

  private ToolCallback recordingCallback(
      Object toolObject, ToolCallback callback, ToolExecutionCollector collector) {
    String recordedToolName =
        toolObject.getClass().getSimpleName() + "." + callback.getToolDefinition().name();
    return new RecordingToolCallback(callback, recordedToolName, collector);
  }

  private Optional<BraveSearchTool> resolveWebSearchTool(String userId) {
    return resolveWebSearchApiKey(userId)
        .map(key -> new BraveSearchTool(key, webSearchHttpClient, webSearchProperties));
  }

  private Optional<String> resolveWebSearchApiKey(String userId) {
    return resolveUserWebSearchApiKey(userId);
  }

  private boolean isToolAllowed(ChatTool tool, String userId, AccessLimits accessLimits) {
    if (accessLimits.allowsTool(tool.getName())) {
      return true;
    }
    if ("brave-search".equals(tool.getName()) && resolveUserWebSearchApiKey(userId).isPresent()) {
      return true;
    }
    if ("url-fetcher".equals(tool.getName()) && userHasAnyProviderKey(userId)) {
      return true;
    }
    return false;
  }

  /**
   * True if the user has at least one non-blank provider key in the keyring. The url-fetcher tool
   * is gated on this so that users who have invested in storing their own API keys get full tool
   * access even on the Free tier.
   */
  private boolean userHasAnyProviderKey(String userId) {
    if (userId == null || userId.isBlank()) {
      return false;
    }
    for (Provider provider : Provider.values()) {
      try {
        if (apiKeyService.hasKey(userId, provider)) {
          Optional<String> key = apiKeyService.getKey(userId, provider);
          if (key.isPresent() && !key.get().isBlank()) {
            return true;
          }
        }
      } catch (RuntimeException e) {
        log.debug("Keyring lookup failed for user={} provider={}", userId, provider, e);
      }
    }
    return false;
  }

  private Optional<String> resolveUserWebSearchApiKey(String userId) {
    return apiKeyService.getKey(userId, Provider.BRAVE_SEARCH).filter(key -> !key.isBlank());
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

  private Map<String, Boolean> loadToolOverrides(UUID chatId) {
    return chatToolRepository.findByChatId(chatId).stream()
        .collect(
            Collectors.toMap(
                ChatToolEntity::getToolName, ChatToolEntity::isEnabled, (first, second) -> second));
  }

  private boolean isToolEnabledForChat(String toolName, Map<String, Boolean> enabledByToolName) {
    Boolean configured = enabledByToolName.get(toolName);
    if (configured != null) {
      return configured;
    }
    return switch (toolName) {
      case "calculator", "current-datetime", "brave-search", "url-fetcher" -> true;
      default -> false;
    };
  }

  private Map<String, Object> toolContext(List<RequestDTO.ToolConsent> toolConsents) {
    if (toolConsents == null || toolConsents.isEmpty()) {
      return Map.of();
    }
    return toolConsents.stream()
        .filter(Objects::nonNull)
        .filter(consent -> consent.toolName() != null && !consent.toolName().isBlank())
        .filter(consent -> consent.args() != null && !consent.args().isEmpty())
        .collect(
            Collectors.toMap(
                consent -> consent.toolName().trim(),
                RequestDTO.ToolConsent::args,
                (first, second) -> second));
  }
}
