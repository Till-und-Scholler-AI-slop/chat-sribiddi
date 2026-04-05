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
import java.util.UUID;
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

  public List<ChatToolResponseDTO> listTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    return chatToolRepository.findByChatId(chatId).stream()
        .map(ChatToolResponseDTO::from)
        .toList();
  }

  public List<AvailableToolDTO> listAvailableTools(UUID chatId, String userId) {
    ensureChatExists(chatId, userId);
    List<ChatToolEntity> configured = chatToolRepository.findByChatId(chatId);
    var enabledNames = configured.stream()
        .filter(ChatToolEntity::isEnabled)
        .map(ChatToolEntity::getToolName)
        .toList();

    return toolRegistry.getAll().stream()
        .map(tool -> new AvailableToolDTO(
            tool.getName(),
            tool.getDisplayName(),
            tool.getDescription(),
            enabledNames.contains(tool.getName())))
        .toList();
  }

  @Transactional
  public ChatToolResponseDTO setTool(UUID chatId, String userId, SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);

    if (!toolRegistry.has(request.toolName())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown tool: " + request.toolName());
    }

    // If enabling a tool that requires per-user config (e.g. API key), verify it exists
    if (request.enabled()) {
      var tool = toolRegistry.get(request.toolName());
      if (tool.isPresent() && tool.get().requiresUserConfig()) {
        // Check if the user has configured the required key
        // (specifically brave-search needs a Brave Search API key)
        if ("brave-search".equals(request.toolName())) {
          if (!apiKeyService.getKey(userId, com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider.BRAVE_SEARCH).isPresent()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cannot enable 'brave-search': no Brave Search API key configured. "
                    + "Please add your API key in the settings first.");
          }
        }
      }
    }

    ChatToolEntity entity =
        chatToolRepository
            .findByChatIdAndToolName(chatId, request.toolName())
            .orElseGet(() -> {
              ChatToolEntity e = new ChatToolEntity();
              e.setChatId(chatId);
              e.setToolName(request.toolName());
              return e;
            });

    entity.setEnabled(request.enabled());
    entity = chatToolRepository.save(entity);
    return ChatToolResponseDTO.from(entity);
  }

  @Transactional
  public void removeTool(UUID chatId, String toolName, String userId) {
    ensureChatExists(chatId, userId);
    if (!toolRegistry.has(toolName)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tool not found: " + toolName);
    }
    chatToolRepository.deleteByChatIdAndToolName(chatId, toolName);
  }

  private void ensureChatExists(UUID chatId, String userId) {
    if (!chatRepository.findByChatIdAndUserId(chatId, userId).isPresent()) {
      throw new ChatNotFoundException(chatId);
    }
  }
}
