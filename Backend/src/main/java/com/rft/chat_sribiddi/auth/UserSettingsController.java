package com.rft.chat_sribiddi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/account/settings")
@RequiredArgsConstructor
public class UserSettingsController {

  private final UserSettingsService userSettingsService;
  private final RequestUserResolver requestUserResolver;

  @GetMapping
  public ResponseEntity<UserSettingsDTO> getSettings(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requireUserId(principal, request);
    String customInstructions =
        userSettingsService.getCustomInstructions(userId).orElse(null);
    return ResponseEntity.ok(new UserSettingsDTO(customInstructions));
  }

  @PutMapping
  public ResponseEntity<Void> updateSettings(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @Valid @RequestBody UserSettingsDTO dto) {
    String userId = requireUserId(principal, request);
    userSettingsService.saveCustomInstructions(userId, dto.customInstructions());
    return ResponseEntity.noContent().build();
  }

  private String requireUserId(OidcUser principal, HttpServletRequest request) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }
}
