package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chats/{chatId}/tools")
@RequiredArgsConstructor
public class ChatToolController {

  private final ChatRepository chatRepository;
  private final ChatToolRepository chatToolRepository;
  private final ToolRegistry toolRegistry;

  /** List all tool configurations for a chat. */
  @GetMapping
  public List<ChatToolResponseDTO> listTools(
      @PathVariable UUID chatId, @RequestUserResolver String userId) {
    ensureChatExists(chatId, userId);
    return chatToolRepository.findByChatId(chatId).stream()
        .map(ChatToolResponseDTO::from)
        .toList();
  }

  /** List all available tools (system-wide, not per-chat). */
  @GetMapping("/available")
  public ResponseEntity<List<AvailableToolDTO>> listAvailableTools(
      @PathVariable UUID chatId, @RequestUserResolver String userId) {
    ensureChatExists(chatId, userId);
    List<ChatToolEntity> configured =
        chatToolRepository.findByChatId(chatId);
    var enabledNames =
        configured.stream()
            .filter(ChatToolEntity::isEnabled)
            .map(ChatToolEntity::getToolName)
            .toList();

    List<AvailableToolDTO> available =
        toolRegistry.getAll().stream()
            .map(
                tool ->
                    new AvailableToolDTO(
                        tool.getName(),
                        tool.getDisplayName(),
                        tool.getDescription(),
                        enabledNames.contains(tool.getName())))
            .toList();
    return ResponseEntity.ok(available);
  }

  /** Enable or disable a tool for a chat. */
  @PutMapping
  public ResponseEntity<ChatToolResponseDTO> setTool(
      @PathVariable UUID chatId,
      @RequestUserResolver String userId,
      @Valid @RequestBody SetChatToolRequestDTO request) {
    ensureChatExists(chatId, userId);

    // Validate tool name exists
    if (!toolRegistry.has(request.toolName())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown tool: " + request.toolName());
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
    return ResponseEntity.ok(ChatToolResponseDTO.from(entity));
  }

  /** Remove a tool configuration for a chat (resets to default). */
  @DeleteMapping("/{toolName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeTool(
      @PathVariable UUID chatId,
      @PathVariable String toolName,
      @RequestUserResolver String userId) {
    ensureChatExists(chatId, userId);
    chatToolRepository.deleteByChatIdAndToolName(chatId, toolName);
  }

  private void ensureChatExists(UUID chatId, String userId) {
    if (!chatRepository.findByChatIdAndUserId(chatId, userId).isPresent()) {
      throw new ChatNotFoundException(chatId);
    }
  }

  public record AvailableToolDTO(
      String name, String displayName, String description, boolean enabled) {}
}
