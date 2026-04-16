package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.service.ChatToolService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/chats/{chatId}/tools")
@RequiredArgsConstructor
public class ChatToolController {

  private final ChatToolService chatToolService;
  private final RequestUserResolver requestUserResolver;

  @GetMapping("/available")
  public ResponseEntity<List<AvailableToolDTO>> listAvailableTools(
      @PathVariable UUID chatId,
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(chatToolService.listAvailableTools(chatId, requireUserId(principal, servletRequest)));
  }

  private String requireUserId(OidcUser principal, HttpServletRequest servletRequest) {
    return requestUserResolver
        .resolveUserId(principal, servletRequest)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }
}
