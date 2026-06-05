package com.rft.chat_sribiddi.access;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import com.rft.chat_sribiddi.providerconnection.ProviderAuthMethod;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ResolvedProviderConnection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CredentialAccessService {

  public static final String OPENROUTER_FREE_MODEL_ID = "openrouter/free";

  private final ApiKeyService apiKeyService;
  private final ProviderConnectionService providerConnectionService;
  private final AccessProperties accessProperties;
  private final Environment environment;

  public ResolvedCredential resolveUserCredential(String userId, Provider provider, String modelId) {
    return resolveUserCredential(userId, provider, modelId, null);
  }

  public ResolvedCredential resolveUserCredential(
      String userId, Provider provider, String modelId, Long connectionId) {
    validateChatProvider(provider);
    validateModelId(modelId);
    if (userId == null || userId.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Authentication is required to use provider connections");
    }
    Optional<ResolvedCredential> connectionCredential =
        readyUserConnection(userId, provider, connectionId);
    if (connectionCredential.isPresent()) {
      return connectionCredential.get();
    }
    if (connectionId != null) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Provider connection is not available for this user");
    }
    Optional<String> userKey = apiKeyService.getKey(userId, provider).filter(key -> !key.isBlank());
    if (userKey.isEmpty()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Connect %s in Settings before using this model".formatted(provider.name()));
    }
    return new ResolvedCredential(userKey.get(), CredentialSource.USER_KEY, authenticatedLimits());
  }

  public Optional<ResolvedCredential> readyUserCredential(String userId, Provider provider) {
    if (userId == null || userId.isBlank() || provider == null || provider == Provider.BRAVE_SEARCH) {
      return Optional.empty();
    }
    Optional<ResolvedCredential> connectionCredential = readyUserConnection(userId, provider, null);
    if (connectionCredential.isPresent()) {
      return connectionCredential;
    }
    return apiKeyService
        .getKey(userId, provider)
        .filter(key -> !key.isBlank())
        .map(key -> new ResolvedCredential(key, CredentialSource.USER_KEY, authenticatedLimits()));
  }

  public ResolvedCredential resolveGuestOpenRouterFree(String modelId) {
    validateModelId(modelId);
    if (!OPENROUTER_FREE_MODEL_ID.equals(modelId)) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MODEL_NOT_ALLOWED,
          "Anonymous chat is restricted to %s".formatted(OPENROUTER_FREE_MODEL_ID));
    }
    String serverKey = environment.getProperty("OPENROUTER_API_KEY");
    if (serverKey == null || serverKey.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED,
          "OpenRouter Free is not configured on the server");
    }
    return new ResolvedCredential(serverKey, CredentialSource.GUEST_OPENROUTER_FREE, guestLimits());
  }

  public Optional<String> guestOpenRouterApiKey() {
    String serverKey = environment.getProperty("OPENROUTER_API_KEY");
    return serverKey == null || serverKey.isBlank() ? Optional.empty() : Optional.of(serverKey);
  }

  public AccessLimits authenticatedLimits() {
    return accessProperties.authenticatedLimits();
  }

  public AccessLimits guestLimits() {
    return accessProperties.guestLimits();
  }

  public List<ModelOption> annotateModels(
      String userId, Provider provider, List<ModelOption> models, boolean hasUserKey) {
    if (models == null || models.isEmpty()) {
      return models == null ? List.of() : models;
    }
    Objects.requireNonNull(provider, "provider must not be null");
    return models.stream()
        .filter(Objects::nonNull)
        .map(
            option -> {
              ModelOption copy = option.copy();
              copy.setAvailability(availability(userId, provider, option, hasUserKey));
              return copy;
            })
        .toList();
  }

  private AvailabilityFlag availability(
      String userId, Provider provider, ModelOption option, boolean hasUserKey) {
    if (hasUserKey) {
      return AvailabilityFlag.READY;
    }
    if ((userId == null || userId.isBlank())
        && provider == Provider.OPENROUTER
        && OPENROUTER_FREE_MODEL_ID.equals(option.getId())) {
      return AvailabilityFlag.READY;
    }
    return AvailabilityFlag.NEEDS_CONNECTION;
  }

  private void validateModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MODEL_NOT_ALLOWED, "Model id must not be blank");
    }
  }

  private void validateChatProvider(Provider provider) {
    Objects.requireNonNull(provider, "provider must not be null");
    if (provider == Provider.BRAVE_SEARCH) {
      throw new IllegalArgumentException("BRAVE_SEARCH is not a chat model provider");
    }
  }

  private Optional<ResolvedCredential> readyUserConnection(
      String userId, Provider provider, Long connectionId) {
    return providerConnectionService
        .resolveReadyConnection(userId, providerId(provider), connectionId)
        .filter(
            connection ->
                connection.hasApiKey()
                    || connection.hasOAuthTokens()
                    || connection.hasGatewayEndpoint())
        .map(connection -> fromConnection(provider, connection));
  }

  private ResolvedCredential fromConnection(
      Provider provider, ResolvedProviderConnection connection) {
    CredentialSource source =
        switch (connection.authMethod()) {
          case OAUTH_AUTH_CODE -> CredentialSource.USER_OAUTH;
          case OAUTH_PKCE -> CredentialSource.USER_OAUTH;
          case DEVICE_CODE -> CredentialSource.USER_DEVICE_OAUTH;
          case LOCAL_ENDPOINT -> CredentialSource.USER_LOCAL_ENDPOINT;
          default -> CredentialSource.USER_KEY;
        };
    String apiKey = connection.apiKey();
    if (!hasText(apiKey)
        && provider != Provider.GOOGLE
        && connection.authMethod() != ProviderAuthMethod.LOCAL_ENDPOINT
        && connection.authMethod() != ProviderAuthMethod.OPENAI_COMPATIBLE) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Provider connection has no usable credential");
    }
    return new ResolvedCredential(
        apiKey,
        source,
        authenticatedLimits(),
        connection.accessToken(),
        connection.refreshToken(),
        connection.clientId(),
        connection.clientSecret(),
        connection.quotaProjectId(),
        connection.tokenExpiresAt(),
        connection.providerId(),
        connection.connectionId(),
        connection.baseUrl(),
        connection.headers(),
        connection.queryParams());
  }

  private String providerId(Provider provider) {
    return switch (provider) {
      case OPENAI -> "openai";
      case GOOGLE -> "google";
      case CLAUDE -> "anthropic";
      case OPENROUTER -> "openrouter";
      case BRAVE_SEARCH -> "brave-search";
    };
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record ResolvedCredential(
      String apiKey,
      CredentialSource credentialSource,
      AccessLimits limits,
      String accessToken,
      String refreshToken,
      String clientId,
      String clientSecret,
      String quotaProjectId,
      Instant tokenExpiresAt,
      String providerId,
      Long connectionId,
      String baseUrl,
      Map<String, String> headers,
      Map<String, String> queryParams) {

    public ResolvedCredential(String apiKey, CredentialSource credentialSource, AccessLimits limits) {
      this(
          apiKey,
          credentialSource,
          limits,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public ResolvedCredential(
        String apiKey,
        CredentialSource credentialSource,
        AccessLimits limits,
        String accessToken,
        String refreshToken,
        String clientId,
        String clientSecret,
        String quotaProjectId,
        Instant tokenExpiresAt,
        String providerId,
        Long connectionId) {
      this(
          apiKey,
          credentialSource,
          limits,
          accessToken,
          refreshToken,
          clientId,
          clientSecret,
          quotaProjectId,
          tokenExpiresAt,
          providerId,
          connectionId,
          null,
          null,
          null);
    }

    public ResolvedCredential {
      Objects.requireNonNull(credentialSource, "credentialSource must not be null");
      Objects.requireNonNull(limits, "limits must not be null");
    }

    public boolean hasApiKey() {
      return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasOAuthTokens() {
      return (accessToken != null && !accessToken.isBlank())
          || (refreshToken != null && !refreshToken.isBlank());
    }

    public boolean hasGatewayEndpoint() {
      return baseUrl != null && !baseUrl.isBlank();
    }

    public String cacheMaterial() {
      if (hasApiKey()) {
        return apiKey;
      }
      if (refreshToken != null && !refreshToken.isBlank()) {
        return refreshToken;
      }
      if (accessToken != null && !accessToken.isBlank()) {
        return accessToken;
      }
      if (baseUrl != null && !baseUrl.isBlank()) {
        return baseUrl + ":" + (apiKey == null ? "" : apiKey);
      }
      return connectionId == null ? credentialSource.name() : connectionId.toString();
    }
  }
}
