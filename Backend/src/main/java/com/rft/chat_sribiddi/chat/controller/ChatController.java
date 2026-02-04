package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@RequestMapping("/chats")
@RestController
public class ChatController {

  private final ChatService chatService;

  @PostMapping
  public ResponseEntity<ChatEntity> createChat(
      @AuthenticationPrincipal OidcUser principal, @RequestBody ChatEntity chatEntity) {
    validatePrincipal(principal);

    ChatEntity savedChat = chatService.createChat(chatEntity, principal.getSubject());

    return ResponseEntity.status(HttpStatus.CREATED).body(savedChat);
  }

  @PostMapping("/{chatId}/messages")
  public ResponseEntity<ChatResponse> sendMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    return ResponseEntity.ok(chatService.sendMessage(chatId, principal.getSubject(), request));
  }

  @DeleteMapping("/{chatId}")
  public HttpStatus deleteChat(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID chatId) {
    validatePrincipal(principal);

    chatService.deleteChat(chatId, principal.getSubject());
    return HttpStatus.OK;
  }

  private void validatePrincipal(OidcUser principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }
}
