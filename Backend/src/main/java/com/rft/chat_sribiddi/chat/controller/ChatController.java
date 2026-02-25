package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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

  @GetMapping
  public ResponseEntity<List<ChatEntity>> listChats(@AuthenticationPrincipal OidcUser principal) {
    validatePrincipal(principal);
    return ResponseEntity.ok(chatService.listChats(principal.getSubject()));
  }

  @GetMapping("/{chatId}/messages")
  public ResponseEntity<List<MessageEntity>> listMessages(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID chatId) {
    validatePrincipal(principal);
    return ResponseEntity.ok(chatService.listMessages(chatId, principal.getSubject()));
  }

  @PostMapping("/{chatId}/messages")
  public ResponseEntity<ChatResponse> sendMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    return ResponseEntity.ok(chatService.sendMessage(chatId, principal.getSubject(), request));
  }

  @PatchMapping("/{chatId}")
  public ResponseEntity<ChatEntity> updateChatTitle(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody UpdateChatPayload payload) {
    validatePrincipal(principal);
    ChatEntity updatedChat =
        chatService.updateChatTitle(chatId, principal.getSubject(), payload.title());
    return ResponseEntity.ok(updatedChat);
  }

  @DeleteMapping("/{chatId}")
  public ResponseEntity<Void> deleteChat(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID chatId) {
    validatePrincipal(principal);

    chatService.deleteChat(chatId, principal.getSubject());
    return ResponseEntity.noContent().build();
  }

  private void validatePrincipal(OidcUser principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }

  public record UpdateChatPayload(@NotBlank(message = "title must not be blank") String title) {}
}
