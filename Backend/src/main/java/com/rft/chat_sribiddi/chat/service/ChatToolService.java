package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.AccessRestrictionException;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.tools.ToolProperties;
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
  private final CredentialAccessService credentialAccessService;
  private final ToolProperties toolProperties;

  @Transactional(readOnly = true)
  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    Collection<ChatTool> tools = toolRegistry.getAll();
    AccessLimits accessLimits = credentialAccessService.authenticatedLimits();
    Map<String, Boolean> enabledByToolName = loadToolOverrides(chatId);
    boolean userBraveSearchConfigured = hasUserWebSearchKey(userId);

    return tools.stream()
        .map(
            tool ->
                new AvailableToolDTO(
                    tool.getName(),
                    tool.getDisplayName(),
                    tool.getDescription(),
                    isToolAvailableAndEnabled(
                        tool,
                        accessLimits,
                        enabledByToolName,
                        userBraveSearchConfigured)))
        .toList();
  }

  @Transactional
  public ChatToolResponseDTO setToolEnabled(
      UUID chatId, String userId, SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);
    String toolName = request.toolName().trim();
    ensureToolRegistered(toolName);
    AccessLimits accessLimits = credentialAccessService.authenticatedLimits();
    if (request.enabled() && !isToolAllowed(toolName, userId, accessLimits)) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.TOOL_NOT_ALLOWED,
          "Tool '%s' is not available".formatted(toolName));
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
      AccessLimits accessLimits,
      Map<String, Boolean> enabledByToolName,
      boolean userBraveSearchConfigured) {
    String toolName = tool.getName();
    return isToolAllowed(
            toolName,
            accessLimits,
            userBraveSearchConfigured)
        && isToolEnabledForUser(
            toolName,
            tool.requiresUserConfig(),
            userBraveSearchConfigured)
        && isToolEnabledForChat(toolName, enabledByToolName);
  }

  private boolean isToolEnabledForUser(
      String toolName,
      boolean requiresUserConfig,
      boolean userBraveSearchConfigured) {
    if (!requiresUserConfig) {
      return true;
    }
    return switch (toolName) {
      case "brave-search" -> userBraveSearchConfigured;
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
    return enabledByToolName.getOrDefault(toolName, toolProperties.getDefaultEnabled().contains(toolName));
  }

  private boolean isToolAllowed(String toolName, String userId, AccessLimits accessLimits) {
    if (accessLimits.allowsTool(toolName)) {
      return true;
    }
    return "brave-search".equals(toolName) && hasUserWebSearchKey(userId);
  }

  private boolean isToolAllowed(
      String toolName,
      AccessLimits accessLimits,
      boolean userBraveSearchConfigured) {
    if (accessLimits.allowsTool(toolName)) {
      return true;
    }
    return "brave-search".equals(toolName) && userBraveSearchConfigured;
  }

  private boolean hasUserWebSearchKey(String userId) {
    return apiKeyService.getKey(userId, Provider.BRAVE_SEARCH).isPresent();
  }
}
