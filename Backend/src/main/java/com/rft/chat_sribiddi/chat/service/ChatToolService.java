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

  private static final Set<String> DEFAULT_ENABLED_TOOLS =
      Set.of("calculator", "current-datetime", "brave-search");

  @Transactional(readOnly = true)
  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    Collection<ChatTool> tools = toolRegistry.getAll();
    Entitlements entitlements = entitlementService.get(userId);
    Map<String, Boolean> enabledByToolName = loadToolOverrides(chatId);
    boolean braveSearchConfigured = isBraveSearchConfigNeeded(tools, entitlements)
        && apiKeyService.getKey(userId, Provider.BRAVE_SEARCH).isPresent();

    return tools.stream()
        .map(
            tool ->
                new AvailableToolDTO(
                    tool.getName(),
                    tool.getDisplayName(),
                    tool.getDescription(),
                    isToolAvailableAndEnabled(
                        tool, entitlements, enabledByToolName, braveSearchConfigured)))
        .toList();
  }

  @Transactional
  public ChatToolResponseDTO setToolEnabled(
      UUID chatId, String userId, SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);
    String toolName = request.toolName().trim();
    ensureToolRegistered(toolName);
    Entitlements entitlements = entitlementService.get(userId);
    if (request.enabled() && !entitlements.allowsTool(toolName)) {
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
      boolean braveSearchConfigured) {
    String toolName = tool.getName();
    return entitlements.allowsTool(toolName)
        && isToolEnabledForUser(toolName, tool.requiresUserConfig(), braveSearchConfigured)
        && isToolEnabledForChat(toolName, enabledByToolName);
  }

  private boolean isToolEnabledForUser(
      String toolName, boolean requiresUserConfig, boolean braveSearchConfigured) {
    if (!requiresUserConfig) {
      return true;
    }
    return switch (toolName) {
      case "brave-search" -> braveSearchConfigured;
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

  private boolean isBraveSearchConfigNeeded(Collection<ChatTool> tools, Entitlements entitlements) {
    return tools.stream()
        .anyMatch(
            tool ->
                "brave-search".equals(tool.getName())
                    && tool.requiresUserConfig()
                    && entitlements.allowsTool(tool.getName()));
  }
}
