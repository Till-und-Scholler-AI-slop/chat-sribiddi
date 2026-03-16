package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/apikeys")
@RequiredArgsConstructor
public class ApiKeyController {

  private final ApiKeyService apiKeyService;
  private final RequestUserResolver requestUserResolver;

  @PutMapping("/{provider}")
  public ResponseEntity<Void> upsertApiKey(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider,
      @Valid @RequestBody ApiKeyPayload payload) {
    String userId = requireUserId(principal, request);
    apiKeyService.saveKey(userId, parseProvider(provider), payload.apiKey());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{provider}")
  public ResponseEntity<Void> deleteApiKey(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    apiKeyService.deleteKey(userId, parseProvider(provider));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{provider}/exists")
  public ResponseEntity<Boolean> hasApiKey(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    boolean exists = apiKeyService.hasKey(userId, parseProvider(provider));
    return ResponseEntity.ok(exists);
  }

  private String requireUserId(OidcUser principal, HttpServletRequest request) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }

  private Provider parseProvider(String provider) {
    try {
      return Provider.valueOf(provider.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
    }
  }

  public record ApiKeyPayload(@NotBlank(message = "apiKey must not be empty") String apiKey) {}
}
