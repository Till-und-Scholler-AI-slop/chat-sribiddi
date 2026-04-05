package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.service.ChatToolService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chats/{chatId}/tools")
@RequiredArgsConstructor
public class ChatToolController {

  private final ChatToolService chatToolService;

  @GetMapping
  public List<ChatToolResponseDTO> listTools(
      @PathVariable UUID chatId, @RequestUserResolver String userId) {
    return chatToolService.listTools(chatId, userId);
  }

  @GetMapping("/available")
  public ResponseEntity<List<AvailableToolDTO>> listAvailableTools(
      @PathVariable UUID chatId, @RequestUserResolver String userId) {
    return ResponseEntity.ok(chatToolService.listAvailableTools(chatId, userId));
  }

  @PutMapping
  public ResponseEntity<ChatToolResponseDTO> setTool(
      @PathVariable UUID chatId,
      @RequestUserResolver String userId,
      @Valid @RequestBody SetChatToolRequestDTO request) {
    return ResponseEntity.ok(chatToolService.setTool(chatId, userId, request));
  }

  @DeleteMapping("/{toolName}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void removeTool(
      @PathVariable UUID chatId,
      @PathVariable String toolName,
      @RequestUserResolver String userId) {
    chatToolService.removeTool(chatId, toolName, userId);
  }
}
