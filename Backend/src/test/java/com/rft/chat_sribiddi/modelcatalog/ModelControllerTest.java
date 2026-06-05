package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import com.rft.chat_sribiddi.access.AccessProperties;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@ExtendWith(MockitoExtension.class)
class ModelControllerTest {

  @Mock private ModelCatalogService modelCatalogService;
  @Mock private ApiKeyService apiKeyService;
  @Mock private ProviderConnectionService providerConnectionService;
  @Mock private HttpServletRequest request;
  @Mock private OidcUser principal;

  private ModelController controller;

  @BeforeEach
  void setUp() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("OPENROUTER_API_KEY", "srv-openrouter");
    CredentialAccessService credentialAccessService =
        new CredentialAccessService(
            apiKeyService, providerConnectionService, new AccessProperties(), environment);
    controller =
        new ModelController(
            modelCatalogService,
            apiKeyService,
            new RequestUserResolver(new MockEnvironment()),
            credentialAccessService,
            new ProviderCatalogService());
  }

  @Test
  void openRouterModels_guestMarksOnlyFreeModelAlways() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(
            new ModelCatalogProperties.ModelOption("openrouter/free", "OpenRouter Free"),
            new ModelCatalogProperties.ModelOption("openrouter/auto", "OpenRouter Auto"));
    when(modelCatalogService.listOpenRouterModels("srv-openrouter")).thenReturn(models);

    var response = controller.openRouterModels(null, request, true);

    assertThat(response.getBody()).hasSize(2);
    assertThat(response.getBody().get(0).getAvailability()).isEqualTo(AvailabilityFlag.READY);
    assertThat(response.getBody().get(1).getAvailability()).isEqualTo(AvailabilityFlag.NEEDS_CONNECTION);
    verify(modelCatalogService).listOpenRouterModels("srv-openrouter");
  }

  @Test
  void openRouterModels_signedInWithoutKeyMarksModelsByokOnly() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openrouter/free", "OpenRouter Free"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.OPENROUTER)).thenReturn(Optional.empty());
    when(modelCatalogService.listOpenRouterModels("srv-openrouter")).thenReturn(models);

    var response = controller.openRouterModels(principal, request, true);

    assertThat(response.getBody()).hasSize(1);
    assertThat(response.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.NEEDS_CONNECTION);
  }

  @Test
  void openRouterModels_prefersUserKeyOverGuestServerKey() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.OPENROUTER))
        .thenReturn(Optional.of("user-key"));
    when(modelCatalogService.listOpenRouterModels("user-key")).thenReturn(models);

    var response = controller.openRouterModels(principal, request, true);

    assertThat(response.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.READY);
    verify(modelCatalogService).listOpenRouterModels("user-key");
  }

  @Test
  void openAiModels_withUserKeyMarksModelsAlways() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.OPENAI)).thenReturn(Optional.of("sk-openai"));
    when(modelCatalogService.listOpenAiModels("sk-openai")).thenReturn(models);

    var response = controller.openAiModels(principal, request, true);

    assertThat(response.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.READY);
  }

  @Test
  void openAiModels_withoutUserKeyMarksModelsByokOnly() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("gpt-4o", "GPT-4o"));
    when(modelCatalogService.listOpenAiModels(null)).thenReturn(models);

    var response = controller.openAiModels(null, request, true);

    assertThat(response.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.NEEDS_CONNECTION);
  }

  @Test
  void openAiModels_compactsAnnotatedModelsWhenExpertModeIsOff() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(
            new ModelCatalogProperties.ModelOption("gpt-5.2", "GPT-5.2"),
            new ModelCatalogProperties.ModelOption("gpt-5.2-mini", "GPT-5.2 Mini"),
            new ModelCatalogProperties.ModelOption("o3-2025-04-16", "o3"),
            new ModelCatalogProperties.ModelOption("gpt-extra", "GPT Extra"));
    List<ModelCatalogProperties.ModelOption> compact = models.subList(0, 3);
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.OPENAI)).thenReturn(Optional.of("sk-openai"));
    when(modelCatalogService.listOpenAiModels("sk-openai")).thenReturn(models);
    when(modelCatalogService.compactModels(eq(Provider.OPENAI), anyList())).thenReturn(compact);

    var response = controller.openAiModels(principal, request, false);

    assertThat(response.getBody()).isEqualTo(compact);
    verify(modelCatalogService).compactModels(eq(Provider.OPENAI), anyList());
  }

  @Test
  void googleAndClaudeModelsUseUserKeys() {
    List<ModelCatalogProperties.ModelOption> google =
        List.of(new ModelCatalogProperties.ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro"));
    List<ModelCatalogProperties.ModelOption> claude =
        List.of(new ModelCatalogProperties.ModelOption("claude-sonnet-4", "Claude Sonnet 4"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.GOOGLE)).thenReturn(Optional.of("sk-google"));
    when(apiKeyService.getKey("user-1", Provider.CLAUDE)).thenReturn(Optional.of("sk-claude"));
    when(modelCatalogService.listGoogleModels("sk-google")).thenReturn(google);
    when(modelCatalogService.listClaudeModels("sk-claude")).thenReturn(claude);

    var googleResponse = controller.googleModels(principal, request, true);
    var claudeResponse = controller.claudeModels(principal, request, true);

    assertThat(googleResponse.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.READY);
    assertThat(claudeResponse.getBody().getFirst().getAvailability()).isEqualTo(AvailabilityFlag.READY);
  }
}
