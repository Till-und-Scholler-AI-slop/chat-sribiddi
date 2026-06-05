package com.rft.chat_sribiddi.providerconnection;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ConnectStartRequest;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ConnectStartResponse;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ProviderConnectionSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/provider-connections")
@RequiredArgsConstructor
public class ProviderConnectionController {

  private final ProviderConnectionService providerConnectionService;
  private final RequestUserResolver requestUserResolver;

  @Value("${app.frontend-url:http://localhost:3000}")
  private String frontendUrl;

  @GetMapping
  public ResponseEntity<List<ProviderConnectionSummary>> list(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requireUserId(principal, request);
    return ResponseEntity.ok(providerConnectionService.listConnections(userId));
  }

  @PostMapping("/{providerId}/api-key")
  public ResponseEntity<ProviderConnectionSummary> saveApiKey(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String providerId,
      @Valid @RequestBody ApiKeyPayload payload) {
    String userId = requireUserId(principal, request);
    return ResponseEntity.ok(
        providerConnectionService.saveCredential(
            userId,
            providerId,
            payload.authMethod(),
            payload.apiKey(),
            payload.baseUrl(),
            payload.headers(),
            payload.queryParams(),
            payload.defaultModelId(),
            payload.displayName()));
  }

  @DeleteMapping("/{providerId}/api-key")
  public ResponseEntity<Void> deleteApiKey(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String providerId) {
    String userId = requireUserId(principal, request);
    providerConnectionService.deleteApiKey(userId, providerId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{providerId}/connect")
  public ResponseEntity<ConnectStartResponse> connect(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String providerId,
      @RequestBody(required = false) ConnectStartRequest payload) {
    String userId = requireUserId(principal, request);
    URI callbackUri =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/provider-connections/{providerId}/callback")
            .buildAndExpand(providerId)
            .toUri();
    return ResponseEntity.ok(
        providerConnectionService.startConnection(
            userId, providerId, payload == null ? null : payload.authMethod(), callbackUri));
  }

  @GetMapping("/{providerId}/callback")
  public ResponseEntity<Void> callback(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String providerId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    String userId = requireUserId(principal, request);
    URI redirect;
    try {
      if (error != null && !error.isBlank()) {
        throw new IllegalArgumentException(error);
      }
      if ("openrouter".equalsIgnoreCase(providerId)) {
        providerConnectionService.completeOpenRouterPkce(userId, state, code);
      } else if ("google".equalsIgnoreCase(providerId)) {
        providerConnectionService.completeGoogleOAuth(userId, state, code);
      } else {
        throw new IllegalArgumentException("Provider callback is not enabled");
      }
      redirect = settingsRedirect(providerId, true, null);
    } catch (Exception ex) {
      redirect = settingsRedirect(providerId, false, ex.getMessage());
    }
    return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
  }

  @DeleteMapping("/{connectionId}")
  public ResponseEntity<Void> deleteConnection(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable Long connectionId) {
    String userId = requireUserId(principal, request);
    providerConnectionService.deleteConnection(userId, connectionId);
    return ResponseEntity.noContent().build();
  }

  private URI settingsRedirect(String providerId, boolean connected, String error) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString(frontendUrl)
            .path("/settings")
            .queryParam("provider", providerId)
            .queryParam("connected", connected ? "1" : "0");
    if (error != null && !error.isBlank()) {
      builder.queryParam("error", error);
    }
    return builder.build().toUri();
  }

  private String requireUserId(OidcUser principal, HttpServletRequest request) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }

  public record ApiKeyPayload(
      @Size(max = 4096, message = "apiKey is too long") String apiKey,
      ProviderAuthMethod authMethod,
      @Size(max = 2048, message = "baseUrl is too long") String baseUrl,
      Map<String, String> headers,
      Map<String, String> queryParams,
      @Size(max = 255, message = "defaultModelId is too long") String defaultModelId,
      @Size(max = 255, message = "displayName is too long") String displayName) {}
}
