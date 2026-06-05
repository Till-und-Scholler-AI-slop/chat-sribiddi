package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.ChatResponseDTO;
import com.rft.chat_sribiddi.chat.dto.ChatDocumentDTO;
import com.rft.chat_sribiddi.chat.dto.CreateChatRequestDTO;
import com.rft.chat_sribiddi.chat.dto.ImportChatsRequestDTO;
import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RunbookExportDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.service.ChatService;
import com.rft.chat_sribiddi.chat.service.ChatDocumentService;
import com.rft.chat_sribiddi.chat.service.GatewayChatService;
import com.rft.chat_sribiddi.chat.service.RunbookExportService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@RequestMapping("/chats")
@RestController
public class ChatController {

  private final ChatService chatService;
  private final ChatDocumentService chatDocumentService;
  private final GatewayChatService gatewayChatService;
  private final RunbookExportService runbookExportService;

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
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer offset) {
    validatePrincipal(principal);
    List<ChatResponseDTO> chats =
        chatService.listChats(principal.getSubject(), limit, offset).stream()
            .map(ChatResponseDTO::from)
            .toList();
    return ResponseEntity.ok(chats);
  }

  @PostMapping("/import")
  public ResponseEntity<List<ChatResponseDTO>> importChats(
      @AuthenticationPrincipal OidcUser principal,
      @Valid @RequestBody ImportChatsRequestDTO request) {
    validatePrincipal(principal);
    List<ChatResponseDTO> chats =
        chatService.importChats(principal.getSubject(), request).stream()
            .map(ChatResponseDTO::from)
            .toList();
    return ResponseEntity.status(HttpStatus.CREATED).body(chats);
  }

  @GetMapping("/{chatId}/messages")
  public ResponseEntity<List<MessageResponseDTO>> listMessages(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer offset) {
    validatePrincipal(principal);
    List<MessageResponseDTO> messages =
        chatService.listMessageResponses(chatId, principal.getSubject(), limit, offset);
    return ResponseEntity.ok(messages);
  }

  @PostMapping(
      value = "/{chatId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ChatDocumentDTO> uploadDocument(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @RequestParam("file") MultipartFile file) {
    validatePrincipal(principal);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(chatDocumentService.upload(chatId, principal.getSubject(), file));
  }

  @GetMapping("/{chatId}/documents")
  public ResponseEntity<List<ChatDocumentDTO>> listDocuments(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId) {
    validatePrincipal(principal);
    return ResponseEntity.ok(chatDocumentService.list(chatId, principal.getSubject()));
  }

  @DeleteMapping("/{chatId}/documents/{documentId}")
  public ResponseEntity<Void> deleteDocument(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @PathVariable Long documentId) {
    validatePrincipal(principal);
    chatDocumentService.delete(chatId, principal.getSubject(), documentId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{chatId}/messages")
  public ResponseEntity<SendMessageResponseDTO> sendMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    if (request.provider() == null) {
      return ResponseEntity.ok(
          gatewayChatService.sendMessage(chatId, principal.getSubject(), request));
    }
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

    if (request.provider() == null) {
      return gatewayChatService.streamMessage(chatId, principal.getSubject(), request);
    }
    return chatService.streamMessage(chatId, principal.getSubject(), request);
  }

  @PostMapping(
      value = "/{chatId}/messages/{messageId}/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter editAndStreamMessage(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID chatId,
      @PathVariable Long messageId,
      @Valid @RequestBody RequestDTO request) {

    validatePrincipal(principal);

    if (request.provider() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Gateway provider edit streaming is not enabled yet");
    }
    return chatService.editAndStreamMessage(chatId, principal.getSubject(), messageId, request);
  }

  @PostMapping("/{chatId}/export/runbook")
  public ResponseEntity<RunbookExportDTO> exportRunbook(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID chatId) {
    validatePrincipal(principal);
    return ResponseEntity.ok(runbookExportService.generateRunbook(chatId, principal.getSubject()));
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
