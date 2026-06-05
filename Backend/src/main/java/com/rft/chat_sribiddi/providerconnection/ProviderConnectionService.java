package com.rft.chat_sribiddi.providerconnection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rft.chat_sribiddi.keyring.CryptoService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService.ProviderDescriptor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderConnectionService {

  private static final TypeReference<Map<String, Object>> SECRET_MAP =
      new TypeReference<>() {};
  private static final Collection<ProviderAuthMethod> API_KEY_COMPATIBLE_METHODS =
      List.of(ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_PKCE);
  private static final Duration TOKEN_REFRESH_SKEW = Duration.ofMinutes(2);

  private final ProviderConnectionRepository repository;
  private final ProviderCatalogService providerCatalogService;
  private final ProviderOAuthProperties providerOAuthProperties;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;
  private final RestClient.Builder restClientBuilder;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Cache<String, PendingOAuthState> oauthStates =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();

  @Transactional(readOnly = true)
  public List<ProviderConnectionSummary> listConnections(String userId) {
    requireUser(userId);
    return repository.findByUserIdOrderByProviderIdAscCreatedAtAsc(userId).stream()
        .map(this::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ProviderCatalogItem> listCatalogWithStatus(String userId) {
    List<ProviderConnectionEntity> connections =
        userId == null || userId.isBlank()
            ? List.of()
            : repository.findByUserIdOrderByProviderIdAscCreatedAtAsc(userId);
    Map<String, List<ProviderConnectionSummary>> byProvider =
        connections.stream().map(this::toSummary).collect(
            java.util.stream.Collectors.groupingBy(
                ProviderConnectionSummary::providerId,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()));

    return providerCatalogService.listProviders().stream()
        .map(
            descriptor -> {
              List<ProviderConnectionSummary> summaries =
                  byProvider.getOrDefault(descriptor.id(), List.of());
              boolean ready =
                  summaries.stream().anyMatch(item -> item.status() == ProviderConnectionStatus.READY);
              ProviderConnectionStatus status =
                  ready ? ProviderConnectionStatus.READY : descriptor.defaultStatus();
              return new ProviderCatalogItem(
                  descriptor.id(),
                  descriptor.label(),
                  descriptor.category(),
                  descriptor.chatProvider(),
                  descriptor.localOnly(),
                  descriptor.experimental(),
                  descriptor.execution(),
                  status,
                  descriptor.authMethods(),
                  summaries);
            })
        .toList();
  }

  @Transactional
  public ProviderConnectionSummary saveApiKey(String userId, String providerId, String apiKey) {
    if (!hasText(apiKey)) {
      throw new IllegalArgumentException("API key must not be empty");
    }
    return saveSecret(
        userId,
        providerId,
        ProviderAuthMethod.API_KEY,
        ProviderConnectionStatus.READY,
        Map.of("apiKey", apiKey.trim()),
        null,
        null);
  }

  @Transactional
  public ProviderConnectionSummary saveCredential(
      String userId,
      String providerId,
      ProviderAuthMethod requestedAuthMethod,
      String apiKey,
      String baseUrl,
      Map<String, String> headers,
      Map<String, String> queryParams,
      String defaultModelId,
      String displayName) {
    ProviderDescriptor descriptor = requireKnownProvider(providerId);
    ProviderAuthMethod authMethod = credentialAuthMethod(descriptor, requestedAuthMethod, baseUrl);
    if (!descriptor.authMethods().contains(authMethod)) {
      throw new IllegalArgumentException(
          "Auth method %s is not supported for provider %s".formatted(authMethod, descriptor.id()));
    }

    Map<String, Object> secret = new LinkedHashMap<>();
    if (hasText(apiKey)) {
      secret.put("apiKey", apiKey.trim());
    }
    if (hasText(baseUrl)) {
      secret.put("baseUrl", normalizeEndpointUrl(baseUrl));
    }
    if (hasText(defaultModelId)) {
      secret.put("modelId", defaultModelId.trim());
    }
    Map<String, String> cleanHeaders = cleanStringMap(headers);
    if (!cleanHeaders.isEmpty()) {
      secret.put("headers", cleanHeaders);
    }
    Map<String, String> cleanQueryParams = cleanStringMap(queryParams);
    if (!cleanQueryParams.isEmpty()) {
      secret.put("queryParams", cleanQueryParams);
    }
    if (secret.isEmpty()) {
      throw new IllegalArgumentException("Provider credential must include an API key or endpoint");
    }
    if (authMethod == ProviderAuthMethod.API_KEY && !secret.containsKey("apiKey")) {
      throw new IllegalArgumentException("API key must not be empty");
    }
    if ((authMethod == ProviderAuthMethod.OPENAI_COMPATIBLE
            || authMethod == ProviderAuthMethod.LOCAL_ENDPOINT)
        && !secret.containsKey("baseUrl")) {
      throw new IllegalArgumentException("Provider endpoint URL is required");
    }

    return saveSecret(
        userId,
        descriptor.id(),
        authMethod,
        ProviderConnectionStatus.READY,
        secret,
        displayName,
        null);
  }

  @Transactional(readOnly = true)
  public Optional<String> getApiKey(String userId, String providerId) {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    return repository
        .findFirstByUserIdAndProviderIdAndAuthMethodInAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
            userId, canonical, API_KEY_COMPATIBLE_METHODS, ProviderConnectionStatus.READY)
        .flatMap(this::apiKeyFromConnection);
  }

  @Transactional
  public Optional<ResolvedProviderConnection> resolveReadyConnection(
      String userId, String providerId) {
    return resolveReadyConnection(userId, providerId, null);
  }

  @Transactional
  public Optional<ResolvedProviderConnection> resolveReadyConnection(
      String userId, String providerId, Long connectionId) {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    if (connectionId != null) {
      return repository
          .findByIdAndUserId(connectionId, userId)
          .filter(entity -> canonical.equals(entity.getProviderId()))
          .filter(entity -> entity.getStatus() == ProviderConnectionStatus.READY)
          .map(entity -> toResolvedConnection(refreshIfNeeded(entity)));
    }
    return repository
        .findFirstByUserIdAndProviderIdAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
            userId, canonical, ProviderConnectionStatus.READY)
        .map(entity -> toResolvedConnection(refreshIfNeeded(entity)));
  }

  @Transactional(readOnly = true)
  public boolean hasReadyConnection(String userId, String providerId) {
    if (userId == null || userId.isBlank()) {
      return false;
    }
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    return repository.existsByUserIdAndProviderIdAndStatus(
        userId, canonical, ProviderConnectionStatus.READY);
  }

  @Transactional
  public void deleteApiKey(String userId, String providerId) {
    requireUser(userId);
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    repository.deleteByUserIdAndProviderIdAndAuthMethod(
        userId, canonical, ProviderAuthMethod.API_KEY);
    repository.deleteByUserIdAndProviderIdAndAuthMethod(
        userId, canonical, ProviderAuthMethod.OAUTH_PKCE);
    repository.deleteByUserIdAndProviderIdAndAuthMethod(
        userId, canonical, ProviderAuthMethod.OAUTH_AUTH_CODE);
  }

  @Transactional
  public void deleteConnection(String userId, Long connectionId) {
    requireUser(userId);
    ProviderConnectionEntity entity =
        repository
            .findByIdAndUserId(connectionId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Provider connection not found"));
    repository.delete(entity);
  }

  @Transactional
  public void deleteByUserId(String userId) {
    if (userId != null && !userId.isBlank()) {
      repository.deleteByUserId(userId);
    }
  }

  public ConnectStartResponse startConnection(
      String userId, String providerId, ProviderAuthMethod method, URI callbackUri) {
    requireUser(userId);
    String canonical = requireKnownProvider(providerId).id();
    ProviderAuthMethod authMethod = method == null ? defaultAuthMethod(canonical) : method;
    if (canonical.equals("openrouter") && authMethod == ProviderAuthMethod.OAUTH_PKCE) {
      return startOpenRouterPkce(userId, callbackUri);
    }
    if (canonical.equals("google") && authMethod == ProviderAuthMethod.OAUTH_AUTH_CODE) {
      return startGoogleOAuth(userId, callbackUri);
    }
    return new ConnectStartResponse(
        "UNAVAILABLE",
        null,
        null,
        null,
        authMethod,
        "This provider auth flow is cataloged but not enabled yet.");
  }

  @Transactional
  public ProviderConnectionSummary completeOpenRouterPkce(
      String userId, String state, String code) {
    requireUser(userId);
    if (state == null || state.isBlank() || code == null || code.isBlank()) {
      throw new IllegalArgumentException("Missing OpenRouter OAuth state or code");
    }
    PendingOAuthState pending = oauthStates.getIfPresent(state);
    if (pending == null || !pending.userId().equals(userId) || !pending.providerId().equals("openrouter")) {
      throw new IllegalArgumentException("OpenRouter OAuth state is invalid or expired");
    }
    oauthStates.invalidate(state);

    OpenRouterKeyResponse response =
        restClientBuilder
            .build()
            .post()
            .uri("https://openrouter.ai/api/v1/auth/keys")
            .body(
                Map.of(
                    "code",
                    code,
                    "code_verifier",
                    pending.codeVerifier(),
                    "code_challenge_method",
                    "S256"))
            .retrieve()
            .body(OpenRouterKeyResponse.class);
    if (response == null || response.key() == null || response.key().isBlank()) {
      throw new IllegalStateException("OpenRouter did not return a user key");
    }
    return saveSecret(
        userId,
        "openrouter",
        ProviderAuthMethod.OAUTH_PKCE,
        ProviderConnectionStatus.READY,
        Map.of("apiKey", response.key()),
        "OpenRouter OAuth",
        null);
  }

  @Transactional
  public ProviderConnectionSummary completeGoogleOAuth(String userId, String state, String code) {
    requireUser(userId);
    if (state == null || state.isBlank() || code == null || code.isBlank()) {
      throw new IllegalArgumentException("Missing Google OAuth state or code");
    }
    PendingOAuthState pending = oauthStates.getIfPresent(state);
    if (pending == null || !pending.userId().equals(userId) || !pending.providerId().equals("google")) {
      throw new IllegalArgumentException("Google OAuth state is invalid or expired");
    }
    oauthStates.invalidate(state);

    ProviderOAuthProperties.Google google = providerOAuthProperties.getGoogle();
    if (!google.isEnabled() || !google.configured()) {
      throw new IllegalStateException("Google provider OAuth is not configured");
    }

    GoogleTokenResponse response =
        exchangeGoogleToken(
            google,
            form(
                "code",
                code,
                "client_id",
                google.getClientId(),
                "client_secret",
                google.getClientSecret(),
                "redirect_uri",
                pending.redirectUri(),
                "grant_type",
                "authorization_code",
                "code_verifier",
                pending.codeVerifier()));
    if (response == null || !hasText(response.accessToken())) {
      throw new IllegalStateException("Google did not return an access token");
    }
    String refreshToken = firstNonBlank(response.refreshToken(), pending.refreshToken());
    if (!hasText(refreshToken)) {
      throw new IllegalStateException("Google did not return a refresh token");
    }

    Instant expiresAt = expiresAt(response.expiresIn());
    List<String> scopes = tokenScopes(response.scope(), google.getScopes());
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("accessToken", response.accessToken());
    secret.put("refreshToken", refreshToken);
    secret.put("clientId", google.getClientId());
    secret.put("clientSecret", google.getClientSecret());
    secret.put("tokenType", firstNonBlank(response.tokenType(), "Bearer"));
    secret.put("scopes", scopes);
    if (hasText(google.getUserProject())) {
      secret.put("quotaProjectId", google.getUserProject().trim());
    }

    return saveSecret(
        userId,
        "google",
        ProviderAuthMethod.OAUTH_AUTH_CODE,
        ProviderConnectionStatus.READY,
        secret,
        "Google OAuth",
        expiresAt,
        scopes,
        null,
        null);
  }

  private ConnectStartResponse startOpenRouterPkce(String userId, URI callbackUri) {
    String state = randomToken(32);
    String verifier = randomToken(64);
    String challenge = sha256Base64Url(verifier);
    URI callbackWithState =
        UriComponentsBuilder.fromUri(callbackUri).queryParam("state", state).build(true).toUri();
    oauthStates.put(
        state,
        new PendingOAuthState(
            userId, "openrouter", verifier, callbackWithState.toString(), null, Instant.now()));
    String authorizationUrl =
        UriComponentsBuilder.fromUriString("https://openrouter.ai/auth")
            .queryParam("callback_url", callbackWithState.toString())
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .encode()
            .toUriString();
    return new ConnectStartResponse(
        "REDIRECT",
        authorizationUrl,
        state,
        Instant.now().plus(Duration.ofMinutes(10)),
        ProviderAuthMethod.OAUTH_PKCE,
        null);
  }

  private ConnectStartResponse startGoogleOAuth(String userId, URI callbackUri) {
    ProviderOAuthProperties.Google google = providerOAuthProperties.getGoogle();
    if (!google.isEnabled() || !google.configured()) {
      return new ConnectStartResponse(
          "UNAVAILABLE",
          null,
          null,
          null,
          ProviderAuthMethod.OAUTH_AUTH_CODE,
          "Google provider OAuth is not configured.");
    }

    String state = randomToken(32);
    String verifier = randomToken(64);
    String challenge = sha256Base64Url(verifier);
    oauthStates.put(
        state,
        new PendingOAuthState(
            userId, "google", verifier, callbackUri.toString(), null, Instant.now()));
    String authorizationUrl =
        UriComponentsBuilder.fromUri(google.getAuthorizationUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", google.getClientId())
            .queryParam("redirect_uri", callbackUri.toString())
            .queryParam("scope", String.join(" ", googleScopes(google)))
            .queryParam("access_type", "offline")
            .queryParam("prompt", "consent")
            .queryParam("state", state)
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .encode()
            .toUriString();
    return new ConnectStartResponse(
        "REDIRECT",
        authorizationUrl,
        state,
        Instant.now().plus(Duration.ofMinutes(10)),
        ProviderAuthMethod.OAUTH_AUTH_CODE,
        null);
  }

  private ProviderConnectionSummary saveSecret(
      String userId,
      String providerId,
      ProviderAuthMethod authMethod,
      ProviderConnectionStatus status,
      Map<String, Object> secret,
      String displayName,
      Instant tokenExpiresAt) {
    return saveSecret(
        userId,
        providerId,
        authMethod,
        status,
        secret,
        displayName,
        tokenExpiresAt,
        null,
        null,
        null);
  }

  private ProviderConnectionSummary saveSecret(
      String userId,
      String providerId,
      ProviderAuthMethod authMethod,
      ProviderConnectionStatus status,
      Map<String, Object> secret,
      String displayName,
      Instant tokenExpiresAt,
      List<String> scopes,
      String providerAccountId,
      String providerAccountLabel) {
    requireUser(userId);
    if (secret == null || secret.isEmpty()) {
      throw new IllegalArgumentException("Provider secret must not be empty");
    }
    ProviderDescriptor descriptor = requireKnownProvider(providerId);
    ProviderConnectionEntity entity =
        repository
            .findFirstByUserIdAndProviderIdAndAuthMethodOrderByCreatedAtAsc(
                userId, descriptor.id(), authMethod)
            .orElseGet(ProviderConnectionEntity::new);
    entity.setUserId(userId);
    entity.setProviderId(descriptor.id());
    entity.setAuthMethod(authMethod);
    entity.setStatus(status);
    entity.setDisplayName(
        displayName == null || displayName.isBlank() ? descriptor.label() : displayName.trim());
    entity.setSecretJson(writeSecret(secret));
    entity.setKeyVersion(cryptoService.activeVersion());
    entity.setTokenExpiresAt(tokenExpiresAt);
    entity.setScopes(scopes == null || scopes.isEmpty() ? null : String.join(" ", scopes));
    entity.setProviderAccountId(providerAccountId);
    entity.setProviderAccountLabel(providerAccountLabel);
    entity.setDefaultConnection(true);
    entity.setLastValidatedAt(Instant.now());
    return toSummary(repository.save(entity));
  }

  private ProviderConnectionEntity refreshIfNeeded(ProviderConnectionEntity entity) {
    if (entity.getAuthMethod() != ProviderAuthMethod.OAUTH_AUTH_CODE
        || !"google".equals(entity.getProviderId())) {
      return entity;
    }
    Instant expiresAt = entity.getTokenExpiresAt();
    if (expiresAt != null && expiresAt.isAfter(Instant.now().plus(TOKEN_REFRESH_SKEW))) {
      return entity;
    }
    Map<String, Object> secret = readSecret(entity.getSecretJson());
    String refreshToken = stringSecret(secret, "refreshToken");
    if (!hasText(refreshToken)) {
      return entity;
    }
    try {
      ProviderOAuthProperties.Google google = providerOAuthProperties.getGoogle();
      String clientId = firstNonBlank(stringSecret(secret, "clientId"), google.getClientId());
      String clientSecret =
          firstNonBlank(stringSecret(secret, "clientSecret"), google.getClientSecret());
      GoogleTokenResponse response =
          exchangeGoogleToken(
              google,
              form(
                  "client_id",
                  clientId,
                  "client_secret",
                  clientSecret,
                  "refresh_token",
                  refreshToken,
                  "grant_type",
                  "refresh_token"));
      if (response == null || !hasText(response.accessToken())) {
        return entity;
      }
      Map<String, Object> updated = new LinkedHashMap<>(secret);
      updated.put("accessToken", response.accessToken());
      updated.put("refreshToken", firstNonBlank(response.refreshToken(), refreshToken));
      updated.put("tokenType", firstNonBlank(response.tokenType(), "Bearer"));
      List<String> scopes = tokenScopes(response.scope(), entityScopes(entity));
      if (!scopes.isEmpty()) {
        updated.put("scopes", scopes);
        entity.setScopes(String.join(" ", scopes));
      }
      entity.setSecretJson(writeSecret(updated));
      entity.setTokenExpiresAt(expiresAt(response.expiresIn()));
      entity.setLastValidatedAt(Instant.now());
      return repository.save(entity);
    } catch (Exception e) {
      log.warn("Failed to refresh Google provider OAuth token for connection {}", entity.getId());
      return entity;
    }
  }

  private GoogleTokenResponse exchangeGoogleToken(
      ProviderOAuthProperties.Google google, MultiValueMap<String, String> body) {
    return restClientBuilder
        .build()
        .post()
        .uri(google.getTokenUri())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(GoogleTokenResponse.class);
  }

  private ProviderDescriptor requireKnownProvider(String providerId) {
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    return providerCatalogService
        .find(canonical)
        .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));
  }

  private ProviderAuthMethod defaultAuthMethod(String providerId) {
    return providerCatalogService
        .find(providerId)
        .map(ProviderDescriptor::authMethods)
        .filter(methods -> !methods.isEmpty())
        .map(methods -> methods.getFirst())
        .orElse(ProviderAuthMethod.API_KEY);
  }

  private Optional<String> apiKeyFromConnection(ProviderConnectionEntity entity) {
    Map<String, Object> secret = readSecret(entity.getSecretJson());
    String value = stringSecret(secret, "apiKey");
    return hasText(value) ? Optional.of(value) : Optional.empty();
  }

  private ResolvedProviderConnection toResolvedConnection(ProviderConnectionEntity entity) {
    Map<String, Object> secret = readSecret(entity.getSecretJson());
    List<String> scopes = tokenScopes(stringSecret(secret, "scope"), entityScopes(entity));
    Object secretScopes = secret.get("scopes");
    if (secretScopes instanceof Collection<?> values) {
      scopes =
          values.stream()
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .filter(this::hasText)
              .toList();
    }
    return new ResolvedProviderConnection(
        entity.getId(),
        entity.getProviderId(),
        entity.getAuthMethod(),
        entity.getStatus(),
        stringSecret(secret, "apiKey"),
        stringSecret(secret, "baseUrl"),
        stringSecret(secret, "modelId"),
        stringMapSecret(secret, "headers"),
        stringMapSecret(secret, "queryParams"),
        stringSecret(secret, "accessToken"),
        stringSecret(secret, "refreshToken"),
        stringSecret(secret, "clientId"),
        stringSecret(secret, "clientSecret"),
        stringSecret(secret, "quotaProjectId"),
        entity.getTokenExpiresAt(),
        scopes,
        entity.getProviderAccountLabel());
  }

  private Map<String, Object> readSecret(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, SECRET_MAP);
    } catch (Exception e) {
      // V16 copies legacy encrypted api_keys rows as-is; after decryption those values are raw keys.
      return Map.of("apiKey", json);
    }
  }

  private String writeSecret(Map<String, Object> secret) {
    try {
      return objectMapper.writeValueAsString(secret);
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialize provider connection secret", e);
    }
  }

  private ProviderConnectionSummary toSummary(ProviderConnectionEntity entity) {
    return new ProviderConnectionSummary(
        entity.getId(),
        entity.getProviderId(),
        entity.getAuthMethod(),
        entity.getStatus(),
        entity.getDisplayName(),
        entity.getProviderAccountLabel(),
        entity.getDefaultConnection() != null && entity.getDefaultConnection(),
        entity.getTokenExpiresAt(),
        entity.getLastValidatedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        stringSecret(readSecret(entity.getSecretJson()), "modelId"));
  }

  private void requireUser(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("Authentication required");
    }
  }

  private String randomToken(int bytes) {
    byte[] value = new byte[bytes];
    secureRandom.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private String sha256Base64Url(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (Exception e) {
      throw new IllegalStateException("Could not create PKCE challenge", e);
    }
  }

  private MultiValueMap<String, String> form(String... values) {
    if (values.length % 2 != 0) {
      throw new IllegalArgumentException("Form values must be key/value pairs");
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    for (int i = 0; i < values.length; i += 2) {
      form.add(values[i], values[i + 1]);
    }
    return form;
  }

  private String stringSecret(Map<String, Object> secret, String key) {
    Object value = secret.get(key);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private Map<String, String> stringMapSecret(Map<String, Object> secret, String key) {
    Object value = secret.get(key);
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() instanceof String mapKey
          && entry.getValue() instanceof String mapValue
          && hasText(mapKey)
          && hasText(mapValue)) {
        result.put(mapKey, mapValue);
      }
    }
    return Map.copyOf(result);
  }

  private Map<String, String> cleanStringMap(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (hasText(key) && hasText(value)) {
            result.put(key.trim(), value.trim());
          }
        });
    return Map.copyOf(result);
  }

  private ProviderAuthMethod credentialAuthMethod(
      ProviderDescriptor descriptor, ProviderAuthMethod requestedAuthMethod, String baseUrl) {
    if (requestedAuthMethod != null) {
      return requestedAuthMethod;
    }
    if (hasText(baseUrl)) {
      if (descriptor.authMethods().contains(ProviderAuthMethod.OPENAI_COMPATIBLE)) {
        return ProviderAuthMethod.OPENAI_COMPATIBLE;
      }
      if (descriptor.authMethods().contains(ProviderAuthMethod.LOCAL_ENDPOINT)) {
        return ProviderAuthMethod.LOCAL_ENDPOINT;
      }
    }
    return ProviderAuthMethod.API_KEY;
  }

  private String normalizeEndpointUrl(String rawUrl) {
    try {
      URI uri = URI.create(rawUrl.trim());
      if (uri.getScheme() == null
          || (!uri.getScheme().equalsIgnoreCase("http")
              && !uri.getScheme().equalsIgnoreCase("https"))) {
        throw new IllegalArgumentException("Provider endpoint URL must use http or https");
      }
      if (hasText(uri.getUserInfo())) {
        throw new IllegalArgumentException("Provider endpoint URL must not include credentials");
      }
      if (hasText(uri.getQuery()) || hasText(uri.getFragment())) {
        throw new IllegalArgumentException("Provider endpoint URL must not include query or fragment");
      }
      return uri.toString().replaceAll("/$", "");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Provider endpoint URL is invalid", e);
    }
  }

  private Instant expiresAt(Long expiresInSeconds) {
    if (expiresInSeconds == null || expiresInSeconds <= 0) {
      return null;
    }
    return Instant.now().plusSeconds(expiresInSeconds);
  }

  private List<String> tokenScopes(String scope, List<String> fallback) {
    if (hasText(scope)) {
      return List.of(scope.trim().split("\\s+"));
    }
    return fallback == null ? List.of() : fallback.stream().filter(this::hasText).toList();
  }

  private List<String> entityScopes(ProviderConnectionEntity entity) {
    return tokenScopes(entity.getScopes(), List.of());
  }

  private List<String> googleScopes(ProviderOAuthProperties.Google google) {
    return google.getScopes() == null ? List.of() : google.getScopes();
  }

  private String firstNonBlank(String first, String second) {
    return hasText(first) ? first : second;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record ResolvedProviderConnection(
      Long connectionId,
      String providerId,
      ProviderAuthMethod authMethod,
      ProviderConnectionStatus status,
      String apiKey,
      String baseUrl,
      String defaultModelId,
      Map<String, String> headers,
      Map<String, String> queryParams,
      String accessToken,
      String refreshToken,
      String clientId,
      String clientSecret,
      String quotaProjectId,
      Instant tokenExpiresAt,
      List<String> scopes,
      String providerAccountLabel) {

    public ResolvedProviderConnection(
        Long connectionId,
        String providerId,
        ProviderAuthMethod authMethod,
        ProviderConnectionStatus status,
        String apiKey,
        String accessToken,
        String refreshToken,
        String clientId,
        String clientSecret,
        String quotaProjectId,
        Instant tokenExpiresAt,
        List<String> scopes,
        String providerAccountLabel) {
      this(
          connectionId,
          providerId,
          authMethod,
          status,
          apiKey,
          null,
          null,
          null,
          null,
          accessToken,
          refreshToken,
          clientId,
          clientSecret,
          quotaProjectId,
          tokenExpiresAt,
          scopes,
          providerAccountLabel);
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
  }

  public record ProviderConnectionSummary(
      Long id,
      String providerId,
      ProviderAuthMethod authMethod,
      ProviderConnectionStatus status,
      String displayName,
      String providerAccountLabel,
      boolean defaultConnection,
      Instant tokenExpiresAt,
      Instant lastValidatedAt,
      Instant createdAt,
      Instant updatedAt,
      String defaultModelId) {}

  public record ProviderCatalogItem(
      String id,
      String label,
      String category,
      boolean chatProvider,
      boolean localOnly,
      boolean experimental,
      String execution,
      ProviderConnectionStatus status,
      List<ProviderAuthMethod> authMethods,
      List<ProviderConnectionSummary> connections) {}

  public record ConnectStartRequest(ProviderAuthMethod authMethod) {}

  public record ConnectStartResponse(
      String mode,
      String authorizationUrl,
      String state,
      Instant expiresAt,
      ProviderAuthMethod authMethod,
      String message) {}

  private record PendingOAuthState(
      String userId,
      String providerId,
      String codeVerifier,
      String redirectUri,
      String refreshToken,
      Instant createdAt) {}

  private record OpenRouterKeyResponse(String key) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record GoogleTokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("refresh_token") String refreshToken,
      @JsonProperty("expires_in") Long expiresIn,
      @JsonProperty("scope") String scope,
      @JsonProperty("token_type") String tokenType) {}
}
