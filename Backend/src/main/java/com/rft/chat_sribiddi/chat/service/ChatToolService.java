package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class ChatToolService {

  private final ChatRepository chatRepository;
  private final ChatToolRepository chatToolRepository;
  private final ToolRegistry toolRegistry;
  private final ApiKeyService apiKeyService;

  private static final Set<String> DEFAULT_ENABLED_TOOLS =
      Set.of("calculator", "current-datetime", "brave-search");

  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    return toolRegistry.getAll().stream()
        .map(tool -> new AvailableToolDTO(
            tool.getName(),
            tool.getDisplayName(),
            tool.getDescription(),
            isToolAvailableAndEnabled(chatId, tool.getName(), tool.requiresUserConfig(), userId)))
        .toList();
  }

  @Transactional
  public ChatToolResponseDTO setToolEnabled(
      UUID chatId, String userId, SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);
    String toolName = request.toolName().trim();
    ensureToolRegistered(toolName);

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

  public boolean isToolEnabled(UUID chatId, String toolName) {
    return chatToolRepository
        .findByChatIdAndToolName(chatId, toolName)
        .map(ChatToolEntity::isEnabled)
        .orElse(DEFAULT_ENABLED_TOOLS.contains(toolName));
  }

  private boolean isToolAvailableAndEnabled(
      UUID chatId, String toolName, boolean requiresUserConfig, String userId) {
    return isToolEnabledForUser(toolName, requiresUserConfig, userId) && isToolEnabled(chatId, toolName);
  }

  private boolean isToolEnabledForUser(String toolName, boolean requiresUserConfig, String userId) {
    if (!requiresUserConfig) {
      return true;
    }
    return switch (toolName) {
      case "brave-search" -> apiKeyService.getKey(userId, Provider.BRAVE_SEARCH).isPresent();
      default -> false;
    };
  }

  private void ensureChatExists(UUID chatId, String userId) {
    if (!chatRepository.findByChatIdAndUserId(chatId, userId).isPresent()) {
      throw new ChatNotFoundException(chatId);
    }
  }

  private void ensureToolRegistered(String toolName) {
    boolean exists = toolRegistry.getAll().stream().anyMatch(tool -> tool.getName().equals(toolName));
    if (!exists) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tool: " + toolName);
    }
  }
}
