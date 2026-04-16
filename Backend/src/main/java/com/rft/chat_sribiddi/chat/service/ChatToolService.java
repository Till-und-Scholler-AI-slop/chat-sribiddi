package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatToolService {

  private final ChatRepository chatRepository;
  private final ToolRegistry toolRegistry;
  private final ApiKeyService apiKeyService;

  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    return toolRegistry.getAll().stream()
        .map(tool -> new AvailableToolDTO(
            tool.getName(),
            tool.getDisplayName(),
            tool.getDescription(),
            isToolEnabledForUser(tool.getName(), tool.requiresUserConfig(), userId)))
        .toList();
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
}
