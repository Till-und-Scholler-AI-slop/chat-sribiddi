package com.rft.chat_sribiddi.providerconnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.keyring.CryptoService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class ProviderConnectionServiceTest {

  @Mock private ProviderConnectionRepository repository;
  @Mock private CryptoService cryptoService;

  private ProviderConnectionService service;
  private ProviderOAuthProperties providerOAuthProperties;

  @BeforeEach
  void setUp() {
    lenient().when(cryptoService.activeVersion()).thenReturn(1);
    providerOAuthProperties = new ProviderOAuthProperties();
    providerOAuthProperties.getGoogle().setClientId("google-client-id");
    providerOAuthProperties.getGoogle().setClientSecret("google-client-secret");
    providerOAuthProperties.getGoogle().setUserProject("quota-project");
    service =
        new ProviderConnectionService(
            repository,
            new ProviderCatalogService(),
            providerOAuthProperties,
            cryptoService,
            new ObjectMapper(),
            RestClient.builder());
  }

  @Test
  void saveApiKey_canonicalizesProviderAndStoresEncryptedSecretPayload() {
    when(repository.findFirstByUserIdAndProviderIdAndAuthMethodOrderByCreatedAtAsc(
            "user-1", "anthropic", ProviderAuthMethod.API_KEY))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var summary = service.saveApiKey("user-1", "CLAUDE", "sk-ant-test");

    ArgumentCaptor<ProviderConnectionEntity> captor =
        ArgumentCaptor.forClass(ProviderConnectionEntity.class);
    verify(repository).save(captor.capture());
    ProviderConnectionEntity saved = captor.getValue();
    assertThat(summary.providerId()).isEqualTo("anthropic");
    assertThat(saved.getUserId()).isEqualTo("user-1");
    assertThat(saved.getProviderId()).isEqualTo("anthropic");
    assertThat(saved.getAuthMethod()).isEqualTo(ProviderAuthMethod.API_KEY);
    assertThat(saved.getStatus()).isEqualTo(ProviderConnectionStatus.READY);
    assertThat(saved.getSecretJson()).contains("sk-ant-test");
  }

  @Test
  void getApiKey_readsOnlyReadyConnectionsForRequestedUserAndProvider() {
    ProviderConnectionEntity entity = new ProviderConnectionEntity();
    entity.setUserId("user-1");
    entity.setProviderId("openai");
    entity.setAuthMethod(ProviderAuthMethod.API_KEY);
    entity.setStatus(ProviderConnectionStatus.READY);
    entity.setSecretJson("{\"apiKey\":\"sk-user\"}");
    when(repository
            .findFirstByUserIdAndProviderIdAndAuthMethodInAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
                "user-1",
                "openai",
                List.of(ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_PKCE),
                ProviderConnectionStatus.READY))
        .thenReturn(Optional.of(entity));

    assertThat(service.getApiKey("user-1", "OPENAI")).contains("sk-user");
  }

  @Test
  void getApiKey_readsLegacyMigratedRawSecretPayload() {
    ProviderConnectionEntity entity = new ProviderConnectionEntity();
    entity.setUserId("user-1");
    entity.setProviderId("openai");
    entity.setAuthMethod(ProviderAuthMethod.API_KEY);
    entity.setStatus(ProviderConnectionStatus.READY);
    entity.setSecretJson("sk-legacy");
    when(repository
            .findFirstByUserIdAndProviderIdAndAuthMethodInAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
                "user-1",
                "openai",
                List.of(ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_PKCE),
                ProviderConnectionStatus.READY))
        .thenReturn(Optional.of(entity));

    assertThat(service.getApiKey("user-1", "OPENAI")).contains("sk-legacy");
  }

  @Test
  void saveCredential_storesOpenAiCompatibleEndpointSecretPayload() {
    when(repository.findFirstByUserIdAndProviderIdAndAuthMethodOrderByCreatedAtAsc(
            "user-1", "mock", ProviderAuthMethod.OPENAI_COMPATIBLE))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var summary =
        service.saveCredential(
            "user-1",
            "mock",
            null,
            "sk-gateway",
            "https://provider.example/v1/",
            Map.of("X-Test", " enabled "),
            Map.of("api-version", "2026-06-05"),
            "mock-model",
            "Mock Gateway");

    ArgumentCaptor<ProviderConnectionEntity> captor =
        ArgumentCaptor.forClass(ProviderConnectionEntity.class);
    verify(repository).save(captor.capture());
    ProviderConnectionEntity saved = captor.getValue();
    assertThat(summary.providerId()).isEqualTo("mock");
    assertThat(saved.getAuthMethod()).isEqualTo(ProviderAuthMethod.OPENAI_COMPATIBLE);
    assertThat(saved.getDisplayName()).isEqualTo("Mock Gateway");
    assertThat(saved.getSecretJson()).contains("\"apiKey\":\"sk-gateway\"");
    assertThat(saved.getSecretJson()).contains("\"baseUrl\":\"https://provider.example/v1\"");
    assertThat(saved.getSecretJson()).contains("\"modelId\":\"mock-model\"");
    assertThat(saved.getSecretJson()).contains("\"X-Test\":\"enabled\"");
  }

  @Test
  void saveCredential_allowsLocalEndpointWithoutApiKey() {
    when(repository.findFirstByUserIdAndProviderIdAndAuthMethodOrderByCreatedAtAsc(
            "user-1", "lm-studio", ProviderAuthMethod.LOCAL_ENDPOINT))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.saveCredential(
        "user-1",
        "lm-studio",
        null,
        null,
        "http://127.0.0.1:1234/v1",
        null,
        null,
        null,
        null);

    ArgumentCaptor<ProviderConnectionEntity> captor =
        ArgumentCaptor.forClass(ProviderConnectionEntity.class);
    verify(repository).save(captor.capture());
    ProviderConnectionEntity saved = captor.getValue();
    assertThat(saved.getAuthMethod()).isEqualTo(ProviderAuthMethod.LOCAL_ENDPOINT);
    assertThat(saved.getSecretJson()).contains("\"baseUrl\":\"http://127.0.0.1:1234/v1\"");
  }

  @Test
  void listCatalogWithStatus_marksOnlyCurrentUsersReadyConnections() {
    ProviderConnectionEntity entity = new ProviderConnectionEntity();
    entity.setId(42L);
    entity.setUserId("user-1");
    entity.setProviderId("openai");
    entity.setAuthMethod(ProviderAuthMethod.API_KEY);
    entity.setStatus(ProviderConnectionStatus.READY);
    entity.setDisplayName("OpenAI");
    when(repository.findByUserIdOrderByProviderIdAscCreatedAtAsc("user-1"))
        .thenReturn(List.of(entity));

    var catalog = service.listCatalogWithStatus("user-1");

    assertThat(catalog)
        .filteredOn(item -> item.id().equals("openai"))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.status()).isEqualTo(ProviderConnectionStatus.READY);
              assertThat(item.connections()).hasSize(1);
            });
    assertThat(catalog)
        .filteredOn(item -> item.id().equals("anthropic"))
        .singleElement()
        .satisfies(item -> assertThat(item.status()).isEqualTo(ProviderConnectionStatus.NEEDS_CONNECTION));
  }

  @Test
  void startConnection_googleOauthReturnsPkceAuthorizationUrl() {
    var response =
        service.startConnection(
            "user-1",
            "google",
            ProviderAuthMethod.OAUTH_AUTH_CODE,
            URI.create("https://germinai.de/provider-connections/google/callback"));

    assertThat(response.mode()).isEqualTo("REDIRECT");
    assertThat(response.authMethod()).isEqualTo(ProviderAuthMethod.OAUTH_AUTH_CODE);
    assertThat(response.state()).isNotBlank();
    Map<String, String> query = queryParams(URI.create(response.authorizationUrl()));
    assertThat(query.get("response_type")).isEqualTo("code");
    assertThat(query.get("client_id")).isEqualTo("google-client-id");
    assertThat(query.get("redirect_uri"))
        .isEqualTo("https://germinai.de/provider-connections/google/callback");
    assertThat(query.get("scope"))
        .contains("https://www.googleapis.com/auth/generative-language.retriever");
    assertThat(query.get("code_challenge")).isNotBlank();
    assertThat(query.get("code_challenge_method")).isEqualTo("S256");
  }

  @Test
  void resolveReadyConnection_mapsGoogleOAuthSecretWithoutExposingAcrossUsers() {
    ProviderConnectionEntity entity = new ProviderConnectionEntity();
    entity.setId(77L);
    entity.setUserId("user-1");
    entity.setProviderId("google");
    entity.setAuthMethod(ProviderAuthMethod.OAUTH_AUTH_CODE);
    entity.setStatus(ProviderConnectionStatus.READY);
    entity.setSecretJson(
        """
        {"accessToken":"access-token","refreshToken":"refresh-token","clientId":"client-id","clientSecret":"client-secret","quotaProjectId":"quota-project","scopes":["scope-a"]}
        """);
    entity.setTokenExpiresAt(Instant.now().plusSeconds(3600));
    when(repository.findFirstByUserIdAndProviderIdAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
            "user-1", "google", ProviderConnectionStatus.READY))
        .thenReturn(Optional.of(entity));

    var resolved = service.resolveReadyConnection("user-1", "GOOGLE");

    assertThat(resolved).isPresent();
    assertThat(resolved.get().connectionId()).isEqualTo(77L);
    assertThat(resolved.get().accessToken()).isEqualTo("access-token");
    assertThat(resolved.get().refreshToken()).isEqualTo("refresh-token");
    assertThat(resolved.get().quotaProjectId()).isEqualTo("quota-project");
    assertThat(resolved.get().scopes()).containsExactly("scope-a");
  }

  @Test
  void resolveReadyConnection_mapsGatewayEndpointSecret() {
    ProviderConnectionEntity entity = new ProviderConnectionEntity();
    entity.setId(88L);
    entity.setUserId("user-1");
    entity.setProviderId("mock");
    entity.setAuthMethod(ProviderAuthMethod.OPENAI_COMPATIBLE);
    entity.setStatus(ProviderConnectionStatus.READY);
    entity.setSecretJson(
        """
        {"apiKey":"sk-gateway","baseUrl":"https://provider.example/v1","modelId":"mock-model","headers":{"X-Test":"enabled"},"queryParams":{"api-version":"2026-06-05"}}
        """);
    when(repository.findFirstByUserIdAndProviderIdAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
            "user-1", "mock", ProviderConnectionStatus.READY))
        .thenReturn(Optional.of(entity));

    var resolved = service.resolveReadyConnection("user-1", "mock");

    assertThat(resolved).isPresent();
    assertThat(resolved.get().hasGatewayEndpoint()).isTrue();
    assertThat(resolved.get().apiKey()).isEqualTo("sk-gateway");
    assertThat(resolved.get().baseUrl()).isEqualTo("https://provider.example/v1");
    assertThat(resolved.get().defaultModelId()).isEqualTo("mock-model");
    assertThat(resolved.get().headers()).containsEntry("X-Test", "enabled");
    assertThat(resolved.get().queryParams()).containsEntry("api-version", "2026-06-05");
  }

  private Map<String, String> queryParams(URI uri) {
    return List.of(uri.getRawQuery().split("&")).stream()
        .map(part -> part.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> decode(pair[0]),
                pair -> pair.length == 2 ? decode(pair[1]) : "",
                (first, _) -> first));
  }

  private String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
