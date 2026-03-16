package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.ChatResponseDTO;
import com.rft.chat_sribiddi.chat.dto.CreateChatRequestDTO;
import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RequiredArgsConstructor
@RequestMapping("/chats")
@RestController
public class ChatController {

  private final ChatService chatService;

  @PostMapping
  public ResponseEntity<ChatResponseDTO> createChat(
      @AuthenticationPrincipal OidcUser principal,
      @RequestBody CreateChatRequestDTO request) {
    validatePrincipal(principal);

    ChatEntity chatEntity = new ChatEntity();
    chatEntity.setTitle(request.title());

    ChatEntity savedChat = chatService.createChat(chatEntity, principal.getSubject());

    return ResponseEntity.status(HttpStatus.CREATED).body(ChatResponseDTO.from(savedChat));
  }

  @GetMapping
  public ResponseEntity<List<ChatResponseDTO>> listChats(
      @AuthenticationPrincipal OidcUser principal) {
    validatePrincipal(principal);
    List<ChatResponseDTO> chats =
        chatService.listChats(principal.getSubject()).stream()
            .map(ChatResponseDTO::from)
            .toList();
    return ResponseEntity.ok(chats);
  }

  @GetMapping("/{chatId}/messages")
  public ResponseEntity<List<MessageResponseDTO>> listMessages(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID chatId) {
    validatePrincipal(principal);
    List<MessageResponseDTO> messages =
        chatService.listMessages(chatId, principal.getSubject()).stream()
            .map(MessageResponseDTO::from)
            .toList();
    return ResponseEntity.ok(messages);
  }

  @PostMapping("/{chatId}/messages")
  public ResponseEntity<SendMessageResponseDTO> sendMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    return ResponseEntity.ok(chatService.sendMessage(chatId, principal.getSubject(), request));
  }

  @PostMapping(
      value = "/{chatId}/messages/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    return chatService.streamMessage(chatId, principal.getSubject(), request);
  }

  @PatchMapping("/{chatId}")
  public ResponseEntity<ChatResponseDTO> updateChatTitle(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody UpdateChatPayload payload) {
    validatePrincipal(principal);
    ChatEntity updatedChat =
        chatService.updateChatTitle(chatId, principal.getSubject(), payload.title());
    return ResponseEntity.ok(ChatResponseDTO.from(updatedChat));
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
