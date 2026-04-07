package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.AvailableToolDTO;
import com.rft.chat_sribiddi.chat.dto.ChatToolResponseDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.service.ChatToolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chats/{chatId}/tools")
@RequiredArgsConstructor
public class ChatToolController {

  private final ChatToolService chatToolService;
  private final RequestUserResolver requestUserResolver;

  @GetMapping
  public ResponseEntity<List<ChatToolResponseDTO>> listTools(
      @PathVariable UUID chatId,
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(chatToolService.listTools(chatId, requireUserId(principal, servletRequest)));
  }

  @GetMapping("/available")
  public ResponseEntity<List<AvailableToolDTO>> listAvailableTools(
      @PathVariable UUID chatId,
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(chatToolService.listAvailableTools(chatId, requireUserId(principal, servletRequest)));
  }

  @PutMapping
  public ResponseEntity<ChatToolResponseDTO> setTool(
      @PathVariable UUID chatId,
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest,
      @Valid @RequestBody SetChatToolRequestDTO toolRequest) {
    return ResponseEntity.ok(chatToolService.setTool(chatId, requireUserId(principal, servletRequest), toolRequest));
  }

  @DeleteMapping("/{toolName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeTool(
      @PathVariable UUID chatId,
      @PathVariable String toolName,
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest) {
    chatToolService.removeTool(chatId, toolName, requireUserId(principal, servletRequest));
  }

  private String requireUserId(OidcUser principal, HttpServletRequest servletRequest) {
    return requestUserResolver
        .resolveUserId(principal, servletRequest)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }
}
