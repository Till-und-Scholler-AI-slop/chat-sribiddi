package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RequestMapping("/chats")
@RestController
public class ChatController {

  private final ChatService chatService;

  @PostMapping
  public ResponseEntity<ChatEntity> createChat(@RequestBody ChatEntity chatEntity) {
    if (chatEntity.getUserId() == null) {
      return ResponseEntity.badRequest().build();
    }

    ChatEntity savedChat = chatService.createChat(chatEntity);

    return ResponseEntity.status(HttpStatus.CREATED).body(savedChat);
  }

  @PostMapping("/{chatId}/messages")
  public ChatResponse sendMessage(@PathVariable UUID chatId, @RequestBody RequestDTO request) {
    return chatService.sendMessage(chatId, request);
  }
}
