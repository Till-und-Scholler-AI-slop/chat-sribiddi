package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.chat.dto.ToolCallLogResponseDTO;
import com.rft.chat_sribiddi.chat.service.ChatService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
@RequestMapping("/messages")
@RestController
public class MessageToolCallController {

  private final ChatService chatService;

  @GetMapping("/{messageId}/tool-calls")
  public ResponseEntity<List<ToolCallLogResponseDTO>> listToolCalls(
      @AuthenticationPrincipal OidcUser principal, @PathVariable Long messageId) {
    validatePrincipal(principal);
    return ResponseEntity.ok(chatService.listToolCallLogs(messageId, principal.getSubject()));
  }

  private void validatePrincipal(OidcUser principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }
}
