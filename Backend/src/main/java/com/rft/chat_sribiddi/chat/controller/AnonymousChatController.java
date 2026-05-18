package com.rft.chat_sribiddi.chat.controller;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.AnonymousChatRequestDTO;
import com.rft.chat_sribiddi.chat.service.AnonymousChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/anonymous/chats")
@RequiredArgsConstructor
public class AnonymousChatController {

  private final AnonymousChatService anonymousChatService;

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamMessage(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest servletRequest,
      @Valid @RequestBody AnonymousChatRequestDTO request) {
    if (principal != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use the authenticated chat endpoint for signed-in sessions");
    }
    String guestId = servletRequest.getHeader(RequestUserResolver.USER_ID_HEADER);
    return anonymousChatService.streamMessage(request, guestId, servletRequest);
  }
}
