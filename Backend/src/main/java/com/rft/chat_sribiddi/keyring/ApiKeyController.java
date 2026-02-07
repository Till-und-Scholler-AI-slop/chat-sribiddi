package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
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

  @PutMapping("/{provider}")
  public ResponseEntity<Void> upsertApiKey(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable String provider,
      @Valid @RequestBody ApiKeyPayload payload) {
    OidcUser user = requireUser(principal);
    apiKeyService.saveKey(user.getSubject(), parseProvider(provider), payload.apiKey());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{provider}")
  public ResponseEntity<Void> deleteApiKey(
      @AuthenticationPrincipal OidcUser principal, @PathVariable String provider) {
    OidcUser user = requireUser(principal);
    apiKeyService.deleteKey(user.getSubject(), parseProvider(provider));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{provider}/exists")
  public ResponseEntity<Boolean> hasApiKey(
      @AuthenticationPrincipal OidcUser principal, @PathVariable String provider) {
    OidcUser user = requireUser(principal);
    boolean exists = apiKeyService.hasKey(user.getSubject(), parseProvider(provider));
    return ResponseEntity.ok(exists);
  }

  private OidcUser requireUser(OidcUser principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    return principal;
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
