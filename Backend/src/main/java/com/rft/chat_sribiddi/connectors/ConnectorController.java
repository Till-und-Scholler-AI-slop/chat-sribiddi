package com.rft.chat_sribiddi.connectors;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.ChatDocumentDTO;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.ConnectorStatusResponse;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.DriveImportRequest;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.GooglePickerTokenResponse;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.OAuthUrlResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/connectors")
@RequiredArgsConstructor
public class ConnectorController {

  private final ConnectorAccountService connectorService;
  private final ConnectorProperties connectorProperties;
  private final GoogleDriveConnectorService googleDriveConnectorService;
  private final RequestUserResolver requestUserResolver;

  @Value("${app.frontend-url:http://localhost:3000}")
  private String frontendUrl;

  @GetMapping
  public ResponseEntity<List<ConnectorStatusResponse>> list(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    return ResponseEntity.ok(connectorService.list(requireUserId(principal, request)));
  }

  @GetMapping("/{provider}")
  public ResponseEntity<ConnectorStatusResponse> status(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    return ResponseEntity.ok(
        connectorService.status(userId, connectorService.parseProvider(provider)));
  }

  @GetMapping("/{provider}/oauth/url")
  public ResponseEntity<OAuthUrlResponse> oauthUrl(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    requireUserId(principal, request);
    ConnectorProvider connectorProvider = connectorService.parseProvider(provider);
    String url =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/connectors/")
            .path(connectorProvider.id())
            .path("/connect")
            .toUriString();
    return ResponseEntity.ok(connectorService.oauthUrl(provider, url));
  }

  @GetMapping("/google-drive/connect")
  public ResponseEntity<Void> connectGoogleDrive(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RegisteredOAuth2AuthorizedClient("google-drive") OAuth2AuthorizedClient authorizedClient) {
    return connect(principal, request, ConnectorProvider.GOOGLE_DRIVE, authorizedClient);
  }

  @GetMapping("/google-gmail/connect")
  public ResponseEntity<Void> connectGoogleGmail(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RegisteredOAuth2AuthorizedClient("google-gmail") OAuth2AuthorizedClient authorizedClient) {
    return connect(principal, request, ConnectorProvider.GOOGLE_GMAIL, authorizedClient);
  }

  @GetMapping("/google-calendar/connect")
  public ResponseEntity<Void> connectGoogleCalendar(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RegisteredOAuth2AuthorizedClient("google-calendar") OAuth2AuthorizedClient authorizedClient) {
    return connect(principal, request, ConnectorProvider.GOOGLE_CALENDAR, authorizedClient);
  }

  @DeleteMapping("/{provider}")
  public ResponseEntity<Void> disconnect(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable String provider) {
    String userId = requireUserId(principal, request);
    connectorService.disconnect(userId, connectorService.parseProvider(provider));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/google-drive/import/{chatId}")
  public ResponseEntity<ChatDocumentDTO> importDriveFile(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @PathVariable UUID chatId,
      @Valid @RequestBody DriveImportRequest payload) {
    String userId = requireUserId(principal, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(googleDriveConnectorService.importFile(chatId, userId, payload));
  }

  @GetMapping("/google-drive/picker-token")
  public ResponseEntity<GooglePickerTokenResponse> googleDrivePickerToken(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requireUserId(principal, request);
    ConnectorProperties.Google google = connectorProperties.getGoogle();
    return ResponseEntity.ok(
        new GooglePickerTokenResponse(
            connectorService.accessToken(userId, ConnectorProvider.GOOGLE_DRIVE),
            google.getPickerDeveloperKey(),
            google.getPickerAppId()));
  }

  private String requireUserId(OidcUser principal, HttpServletRequest request) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }

  private ResponseEntity<Void> connect(
      OidcUser principal,
      HttpServletRequest request,
      ConnectorProvider provider,
      OAuth2AuthorizedClient authorizedClient) {
    String userId = requireUserId(principal, request);
    connectorService.markConnected(userId, provider, authorizedClient);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(
            HttpHeaders.LOCATION,
            frontendUrl.replaceAll("/+$", "") + "/settings?connector=" + provider.id())
        .build();
  }
}
