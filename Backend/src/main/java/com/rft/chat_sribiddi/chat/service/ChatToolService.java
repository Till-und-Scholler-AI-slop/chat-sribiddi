package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatToolService {

  private final ChatRepository chatRepository;
  private final ChatToolRepository chatToolRepository;
  private final ToolRegistry toolRegistry;
  private final ApiKeyService apiKeyService;
  private final EntitlementService entitlementService;
  private final WebSearchProperties webSearchProperties;

  private static final Set<String> DEFAULT_ENABLED_TOOLS =
      Set.of("calculator", "current-datetime", "brave-search");

  @Transactional(readOnly = true)
  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    Collection<ChatTool> tools = toolRegistry.getAll();
    Entitlements entitlements = entitlementService.get(userId);
    Map<String, Boolean> enabledByToolName = loadToolOverrides(chatId);
    boolean userBraveSearchConfigured = hasUserWebSearchKey(userId);
    boolean serverBraveSearchConfigured =
        isServerBraveSearchConfigNeeded(tools, entitlements) && hasServerWebSearchKey();

    return tools.stream()
        .map(
            tool ->
                new AvailableToolDTO(
                    tool.getName(),
                    tool.getDisplayName(),
                    tool.getDescription(),
                    isToolAvailableAndEnabled(
                        tool,
                        entitlements,
                        enabledByToolName,
                        userBraveSearchConfigured,
                        serverBraveSearchConfigured)))
        .toList();
  }

  @Transactional
  public ChatToolResponseDTO setToolEnabled(
      UUID chatId, String userId, SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);
    String toolName = request.toolName().trim();
    ensureToolRegistered(toolName);
    Entitlements entitlements = entitlementService.get(userId);
    if (request.enabled() && !isToolAllowed(toolName, userId, entitlements)) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.TOOL_NOT_ALLOWED,
          entitlements.plan(),
          "Tool '%s' is not available on the current plan".formatted(toolName));
    }

    ChatToolEntity entity =
        chatToolRepository
            .findByChatIdAndToolName(chatId, toolName)
            .orElseGet(
                () -> {
                  ChatToolEntity created = new ChatToolEntity();
                  created.setChatId(chatId);
                  created.setToolName(toolName);
                  return created;
                });
    entity.setEnabled(request.enabled());
    return ChatToolResponseDTO.from(chatToolRepository.save(entity));
  }

  private boolean isToolAvailableAndEnabled(
      ChatTool tool,
      Entitlements entitlements,
      Map<String, Boolean> enabledByToolName,
      boolean userBraveSearchConfigured,
      boolean serverBraveSearchConfigured) {
    String toolName = tool.getName();
    return isToolAllowed(
            toolName,
            entitlements,
            userBraveSearchConfigured)
        && isToolEnabledForUser(
            toolName,
            tool.requiresUserConfig(),
            userBraveSearchConfigured,
            serverBraveSearchConfigured)
        && isToolEnabledForChat(toolName, enabledByToolName);
  }

  private boolean isToolEnabledForUser(
      String toolName,
      boolean requiresUserConfig,
      boolean userBraveSearchConfigured,
      boolean serverBraveSearchConfigured) {
    if (!requiresUserConfig) {
      return true;
    }
    return switch (toolName) {
      case "brave-search" -> userBraveSearchConfigured || serverBraveSearchConfigured;
      default -> false;
    };
  }

  private void ensureChatExists(UUID chatId, String userId) {
    if (!chatRepository.findByChatIdAndUserId(chatId, userId).isPresent()) {
      throw new ChatNotFoundException(chatId);
    }
  }

  private void ensureToolRegistered(String toolName) {
    if (!toolRegistry.has(toolName)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tool: " + toolName);
    }
  }

  private Map<String, Boolean> loadToolOverrides(UUID chatId) {
    return chatToolRepository.findByChatId(chatId).stream()
        .collect(
            Collectors.toMap(
                ChatToolEntity::getToolName, ChatToolEntity::isEnabled, (first, second) -> second));
  }

  private boolean isToolEnabledForChat(String toolName, Map<String, Boolean> enabledByToolName) {
    return enabledByToolName.getOrDefault(toolName, DEFAULT_ENABLED_TOOLS.contains(toolName));
  }

  private boolean isServerBraveSearchConfigNeeded(
      Collection<ChatTool> tools, Entitlements entitlements) {
    return tools.stream()
        .anyMatch(
            tool ->
                "brave-search".equals(tool.getName())
                    && tool.requiresUserConfig()
                    && entitlements.allowsTool(tool.getName()));
  }

  private boolean hasServerWebSearchKey() {
    String apiKey = webSearchProperties.getApiKey();
    return apiKey != null && !apiKey.isBlank();
  }

  private boolean isToolAllowed(String toolName, String userId, Entitlements entitlements) {
    if (entitlements.allowsTool(toolName)) {
      return true;
    }
    return "brave-search".equals(toolName) && hasUserWebSearchKey(userId);
  }

  private boolean isToolAllowed(
      String toolName,
      Entitlements entitlements,
      boolean userBraveSearchConfigured) {
    if (entitlements.allowsTool(toolName)) {
      return true;
    }
    return "brave-search".equals(toolName) && userBraveSearchConfigured;
  }

  private boolean hasUserWebSearchKey(String userId) {
    return apiKeyService.getKey(userId, Provider.BRAVE_SEARCH).isPresent();
  }
}
