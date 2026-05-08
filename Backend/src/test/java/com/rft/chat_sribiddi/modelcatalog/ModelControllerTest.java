package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.ManagedApiKeyProperties;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
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
  @Mock private EntitlementService entitlementService;
  @Mock private HttpServletRequest request;
  @Mock private OidcUser principal;

  private ManagedApiKeyProperties managedApiKeyProperties;
  private ModelController controller;

  @BeforeEach
  void setUp() {
    managedApiKeyProperties = new ManagedApiKeyProperties();
    controller =
        new ModelController(
            modelCatalogService,
            apiKeyService,
            new RequestUserResolver(new MockEnvironment()),
            entitlementService,
            managedApiKeyProperties);
  }

  @Test
  void openRouterModels_returnsConfiguredModelsWhenPrincipalMissing() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openrouter/auto", "OpenRouter Auto"));
    when(modelCatalogService.listOpenRouterModels(null)).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan(null, Provider.OPENROUTER, models, false))
        .thenReturn(models);

    var response = controller.openRouterModels(null, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(modelCatalogService).listOpenRouterModels(null);
    verify(entitlementService).filterUsableModelsForPlan(null, Provider.OPENROUTER, models, false);
  }

  @Test
  void openRouterModels_prefersAuthenticatedUserOverRequestHeader() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("oidc-user");
    when(apiKeyService.getKey("oidc-user", Provider.OPENROUTER)).thenReturn(Optional.of("or-oidc-key"));
    when(modelCatalogService.listOpenRouterModels("or-oidc-key")).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan("oidc-user", Provider.OPENROUTER, models, true))
        .thenReturn(models);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(apiKeyService).getKey("oidc-user", Provider.OPENROUTER);
    verify(modelCatalogService).listOpenRouterModels("or-oidc-key");
  }

  @Test
  void openRouterModels_returnsOnlyUsableFreeTierModels() {
    // Free users receive only the usable subset; paid models are filtered out by
    // EntitlementService before the catalog reaches the UI.
    List<ModelCatalogProperties.ModelOption> all =
        List.of(
            new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"),
            new ModelCatalogProperties.ModelOption(
                "meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 (free)"));
    List<ModelCatalogProperties.ModelOption> usable = List.of(all.get(1));
    when(principal.getSubject()).thenReturn("free-user");
    when(apiKeyService.getKey("free-user", Provider.OPENROUTER)).thenReturn(Optional.empty());
    // No managed key configured → catalog is fetched with null (YAML fallback path).
    when(modelCatalogService.listOpenRouterModels(null)).thenReturn(all);
    when(entitlementService.filterUsableModelsForPlan("free-user", Provider.OPENROUTER, all, false))
        .thenReturn(usable);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(usable);
  }

  @Test
  void openRouterModels_fallsBackToManagedKey_whenUserHasNoByokKey() {
    // Free-tier users typically don't have a BYOK key. The controller must fall back to the
    // server-managed OpenRouter key so the catalog is fetched live (and actually contains
    // `:free` ids) rather than returning the empty YAML fallback.
    List<ModelCatalogProperties.ModelOption> live =
        List.of(
            new ModelCatalogProperties.ModelOption(
                "meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 (free)"));
    managedApiKeyProperties.setOpenrouter("srv-openrouter");
    when(principal.getSubject()).thenReturn("free-user");
    when(apiKeyService.getKey("free-user", Provider.OPENROUTER)).thenReturn(Optional.empty());
    when(modelCatalogService.listOpenRouterModels("srv-openrouter")).thenReturn(live);
    when(entitlementService.filterUsableModelsForPlan("free-user", Provider.OPENROUTER, live, false))
        .thenReturn(live);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(live);
    verify(modelCatalogService).listOpenRouterModels("srv-openrouter");
  }

  @Test
  void openRouterModels_prefersByokKeyOverManagedKey() {
    // A BYOK user key must take precedence over the server-managed fallback so paying users'
    // catalog results stay bound to their own account/rate-limits.
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"));
    managedApiKeyProperties.setOpenrouter("srv-openrouter");
    when(principal.getSubject()).thenReturn("premium-user");
    when(apiKeyService.getKey("premium-user", Provider.OPENROUTER))
        .thenReturn(Optional.of("user-key"));
    when(modelCatalogService.listOpenRouterModels("user-key")).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan(
            "premium-user", Provider.OPENROUTER, models, true))
        .thenReturn(models);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(modelCatalogService).listOpenRouterModels("user-key");
  }

  @Test
  void openAiModels_filtersModelsForPlan() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.OPENAI)).thenReturn(Optional.of("sk-openai"));
    when(modelCatalogService.listOpenAiModels("sk-openai")).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan("user-1", Provider.OPENAI, models, true))
        .thenReturn(models);

    var response = controller.openAiModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(entitlementService).filterUsableModelsForPlan("user-1", Provider.OPENAI, models, true);
  }

  @Test
  void googleModels_filtersModelsForPlan() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.GOOGLE)).thenReturn(Optional.of("sk-google"));
    when(modelCatalogService.listGoogleModels("sk-google")).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan("user-1", Provider.GOOGLE, models, true))
        .thenReturn(models);

    var response = controller.googleModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(entitlementService).filterUsableModelsForPlan("user-1", Provider.GOOGLE, models, true);
  }

  @Test
  void claudeModels_filtersModelsForPlan() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("claude-sonnet-4", "Claude Sonnet 4"));
    when(principal.getSubject()).thenReturn("user-1");
    when(apiKeyService.getKey("user-1", Provider.CLAUDE)).thenReturn(Optional.of("sk-claude"));
    when(modelCatalogService.listClaudeModels("sk-claude")).thenReturn(models);
    when(entitlementService.filterUsableModelsForPlan("user-1", Provider.CLAUDE, models, true))
        .thenReturn(models);

    var response = controller.claudeModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(entitlementService).filterUsableModelsForPlan("user-1", Provider.CLAUDE, models, true);
  }
}
