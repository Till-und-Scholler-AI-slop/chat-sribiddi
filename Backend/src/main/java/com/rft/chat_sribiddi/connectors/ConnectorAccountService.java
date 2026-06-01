package com.rft.chat_sribiddi.connectors;

import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.ConnectorStatusResponse;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.OAuthUrlResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ConnectorAccountService {

  private final ConnectorAccountRepository repository;
  private final ConnectorProperties properties;
  private final EntitlementService entitlementService;
  private final ClientRegistrationRepository clientRegistrationRepository;
  private final OAuth2AuthorizedClientService authorizedClientService;
  private final OAuth2AuthorizedClientManager authorizedClientManager;

  public List<ConnectorProvider> providers() {
    return List.of(
        ConnectorProvider.GOOGLE_DRIVE,
        ConnectorProvider.GOOGLE_GMAIL,
        ConnectorProvider.GOOGLE_CALENDAR);
  }

  @Transactional(readOnly = true)
  public List<ConnectorStatusResponse> list(String userId) {
    return providers().stream().map(provider -> status(userId, provider)).toList();
  }

  @Transactional(readOnly = true)
  public ConnectorStatusResponse status(String userId, ConnectorProvider provider) {
    Optional<ConnectorAccountEntity> account = repository.findByUserIdAndProvider(userId, provider);
    OAuth2AuthorizedClient client = loadAuthorizedClient(userId, provider);
    ConnectorStatus status =
        configured(provider)
            ? client != null
                ? ConnectorStatus.CONNECTED
                : account.isPresent()
                    ? ConnectorStatus.NEEDS_RECONNECT
                    : ConnectorStatus.NOT_CONNECTED
            : ConnectorStatus.UNAVAILABLE;
    boolean planAllowed = planAllowed(userId, provider);
    return new ConnectorStatusResponse(
        provider.id(),
        status,
        configured(provider),
        status == ConnectorStatus.CONNECTED,
        scopes(provider),
        client != null || account.isPresent() ? "spring-oauth2-authorized-client" : null,
        account.map(ConnectorAccountEntity::getLastUsedAt).orElse(null),
        planAllowed);
  }

  @Transactional(readOnly = true)
  public boolean isConnected(String userId, ConnectorProvider provider) {
    return configured(provider) && loadAuthorizedClient(userId, provider) != null;
  }

  public OAuthUrlResponse oauthUrl(String providerId, String url) {
    ConnectorProvider provider = parseProvider(providerId);
    if (!configured(provider)) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Connector is not configured");
    }
    return new OAuthUrlResponse(url);
  }

  @Transactional
  public void markConnected(String userId, ConnectorProvider provider, OAuth2AuthorizedClient client) {
    ConnectorAccountEntity entity =
        repository.findByUserIdAndProvider(userId, provider).orElseGet(ConnectorAccountEntity::new);
    entity.setUserId(userId);
    entity.setProvider(provider);
    entity.setRefreshToken(null);
    entity.setGrantedScopes(String.join(" ", scopes(provider)));
    entity.setStatus(ConnectorStatus.CONNECTED);
    entity.setTokenExpiresAt(client.getAccessToken().getExpiresAt());
    entity.setLastUsedAt(Instant.now());
    repository.save(entity);
  }

  @Transactional
  public void disconnect(String userId, ConnectorProvider provider) {
    authorizedClientService.removeAuthorizedClient(provider.registrationId(), userId);
    repository.deleteByUserIdAndProvider(userId, provider);
  }

  @Transactional
  public String accessToken(String userId, ConnectorProvider provider) {
    OAuth2AuthorizedClient client =
        authorizedClientManager.authorize(
            OAuth2AuthorizeRequest.withClientRegistrationId(provider.registrationId())
                .principal(principal(userId))
                .build());
    if (client == null || client.getAccessToken() == null) {
      markNeedsReconnect(userId, provider);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector needs reconnect");
    }
    repository
        .findByUserIdAndProvider(userId, provider)
        .ifPresent(
            account -> {
              account.setStatus(ConnectorStatus.CONNECTED);
              account.setTokenExpiresAt(client.getAccessToken().getExpiresAt());
              account.setLastUsedAt(Instant.now());
              repository.save(account);
            });
    return client.getAccessToken().getTokenValue();
  }

  public ConnectorProvider parseProvider(String providerId) {
    try {
      return ConnectorProvider.parse(providerId);
    } catch (IllegalArgumentException _) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown connector: " + providerId);
    }
  }

  public List<String> scopes(ConnectorProvider provider) {
    ClientRegistration registration = clientRegistration(provider);
    if (registration != null && !registration.getScopes().isEmpty()) {
      return List.copyOf(registration.getScopes());
    }
    return switch (provider) {
      case GOOGLE_DRIVE -> List.of("https://www.googleapis.com/auth/drive.file");
      case GOOGLE_GMAIL -> List.of("https://www.googleapis.com/auth/gmail.readonly");
      case GOOGLE_CALENDAR ->
          List.of(
              "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
              "https://www.googleapis.com/auth/calendar.events.readonly",
              "https://www.googleapis.com/auth/calendar.freebusy");
    };
  }

  public boolean configured(ConnectorProvider provider) {
    if (provider == ConnectorProvider.GOOGLE_GMAIL && !properties.getGoogle().isGmailEnabled()) {
      return false;
    }
    return clientRegistration(provider) != null;
  }

  private OAuth2AuthorizedClient loadAuthorizedClient(String userId, ConnectorProvider provider) {
    return authorizedClientService.loadAuthorizedClient(provider.registrationId(), userId);
  }

  private ClientRegistration clientRegistration(ConnectorProvider provider) {
    return clientRegistrationRepository.findByRegistrationId(provider.registrationId());
  }

  private boolean planAllowed(String userId, ConnectorProvider provider) {
    String toolName =
        switch (provider) {
          case GOOGLE_DRIVE -> "google-drive";
          case GOOGLE_GMAIL -> "gmail-search";
          case GOOGLE_CALENDAR -> "calendar-list-events";
        };
    try {
      return entitlementService.get(userId).allowsTool(toolName);
    } catch (RuntimeException _) {
      return false;
    }
  }

  private void markNeedsReconnect(String userId, ConnectorProvider provider) {
    repository
        .findByUserIdAndProvider(userId, provider)
        .ifPresent(
            account -> {
              account.setStatus(ConnectorStatus.NEEDS_RECONNECT);
              repository.save(account);
            });
  }

  private static Authentication principal(String userId) {
    return UsernamePasswordAuthenticationToken.authenticated(userId, "N/A", List.of());
  }
}
