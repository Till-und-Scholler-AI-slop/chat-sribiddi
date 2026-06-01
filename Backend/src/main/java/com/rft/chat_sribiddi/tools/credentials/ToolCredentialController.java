package com.rft.chat_sribiddi.tools.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.auth.RequestUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/tool-credentials")
@RequiredArgsConstructor
public class ToolCredentialController {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String GITHUB_STATE_SESSION_KEY = "github_tool_oauth_state";

  private final ToolCredentialService credentialService;
  private final ToolCredentialProperties properties;
  private final RequestUserResolver requestUserResolver;
  private final HttpClient httpClient;

  @Value("${app.frontend-url:http://localhost:3000}")
  private String frontendUrl;

  @PutMapping("/{provider}")
  public ResponseEntity<Void> upsertSecret(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider,
      @Valid @RequestBody CredentialPayload payload) {
    String userId = requireUserId(principal, request);
    credentialService.saveSecret(userId, parseProvider(provider), payload.secret());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{provider}")
  public ResponseEntity<Void> deleteSecret(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    credentialService.deleteSecret(userId, parseProvider(provider));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{provider}/exists")
  public ResponseEntity<CredentialStatus> status(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    ToolCredentialProvider parsed = parseProvider(provider);
    boolean userConfigured = credentialService.hasSecret(userId, parsed);
    boolean managedConfigured =
        switch (parsed) {
          case OPENAI_IMAGE -> hasText(properties.getOpenaiImageApiKey());
          case GITHUB -> hasText(properties.getGithubToken());
        };
    return ResponseEntity.ok(new CredentialStatus(userConfigured, managedConfigured));
  }

  @GetMapping("/github/oauth/url")
  public ResponseEntity<OAuthUrlResponse> githubOAuthUrl(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    requireUserId(principal, request);
    if (!hasText(properties.getGithubClientId()) || !hasText(properties.getGithubRedirectUri())) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth is not configured");
    }
    String state = UUID.randomUUID().toString();
    request.getSession(true).setAttribute(GITHUB_STATE_SESSION_KEY, state);
    String url =
        "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=read:user&state=%s"
            .formatted(
                encode(properties.getGithubClientId()),
                encode(properties.getGithubRedirectUri()),
                encode(state));
    return ResponseEntity.ok(new OAuthUrlResponse(url));
  }

  @GetMapping("/github/oauth/callback")
  public ResponseEntity<Void> githubOAuthCallback(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam String code,
      @RequestParam String state) {
    String userId = requireUserId(principal, request);
    HttpSession session = request.getSession(false);
    Object expectedState =
        session == null ? null : session.getAttribute(GITHUB_STATE_SESSION_KEY);
    if (expectedState == null || !expectedState.equals(state)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
    }
    session.removeAttribute(GITHUB_STATE_SESSION_KEY);
    String token = exchangeGithubCode(code);
    credentialService.saveSecret(userId, ToolCredentialProvider.GITHUB, token);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, frontendUrl.replaceAll("/+$", "") + "/settings?github=connected")
        .build();
  }

  private String exchangeGithubCode(String code) {
    if (!hasText(properties.getGithubClientId())
        || !hasText(properties.getGithubClientSecret())
        || !hasText(properties.getGithubRedirectUri())) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth is not configured");
    }
    String body =
        "client_id=%s&client_secret=%s&code=%s&redirect_uri=%s"
            .formatted(
                encode(properties.getGithubClientId()),
                encode(properties.getGithubClientSecret()),
                encode(code),
                encode(properties.getGithubRedirectUri()));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub OAuth exchange failed");
      }
      JsonNode root = MAPPER.readTree(response.body());
      String accessToken = root.path("access_token").asText(null);
      if (!hasText(accessToken)) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub OAuth returned no token");
      }
      return accessToken;
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub OAuth exchange failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub OAuth exchange interrupted", e);
    }
  }

  private String requireUserId(OidcUser principal, HttpServletRequest request) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }

  private ToolCredentialProvider parseProvider(String provider) {
    String normalized = provider.replace('-', '_').toUpperCase(Locale.ROOT);
    try {
      return ToolCredentialProvider.valueOf(normalized);
    } catch (IllegalArgumentException _) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tool credential provider: " + provider);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record CredentialPayload(@NotBlank(message = "secret must not be empty") String secret) {}

  public record CredentialStatus(boolean userConfigured, boolean managedConfigured) {}

  public record OAuthUrlResponse(String url) {}
}
