package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/chats")
@RestController
public class ChatController {

  private final ChatService chatService;

  @PostMapping
  public ResponseEntity<ChatEntity> createChat(@RequestBody ChatEntity chatEntity) {
    if (chatEntity.getUserId().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    ChatEntity savedChat = chatService.createChat(chatEntity);

    return ResponseEntity.status(HttpStatus.CREATED).body(savedChat);
  }

  @PostMapping("/{chatId}/messages")
  public ResponseEntity<ChatResponse> sendMessage(
      @PathVariable UUID chatId, @RequestBody RequestDTO request) {

    return ResponseEntity.ok(chatService.sendMessage(chatId, request));
  }

  @DeleteMapping("/{chatId}")
  public HttpStatus deleteChat(@PathVariable UUID chatId) {
    try {
      chatService.deleteChat(chatId);
      return HttpStatus.OK;

    } catch (Exception e) {
      log.error("Error in deleting chat: {}", e.getMessage());
      return HttpStatus.INTERNAL_SERVER_ERROR;
    }
  }
}
