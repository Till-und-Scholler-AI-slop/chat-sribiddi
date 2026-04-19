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
            new RequestUserResolver(),
            entitlementService,
            managedApiKeyProperties);
  }

  @Test
  void openRouterModels_usesFrontendUserIdFallback_whenPrincipalMissing() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openrouter/auto", "OpenRouter Auto"));
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn("frontend-user");
    when(apiKeyService.getKey("frontend-user", Provider.OPENROUTER)).thenReturn(Optional.of("or-user-key"));
    when(modelCatalogService.listOpenRouterModels("or-user-key")).thenReturn(models);
    when(entitlementService.filterOpenRouterModelsForPlan("frontend-user", models))
        .thenReturn(models);

    var response = controller.openRouterModels(null, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(apiKeyService).getKey("frontend-user", Provider.OPENROUTER);
    verify(modelCatalogService).listOpenRouterModels("or-user-key");
    verify(entitlementService).filterOpenRouterModelsForPlan("frontend-user", models);
  }

  @Test
  void openRouterModels_prefersAuthenticatedUserOverRequestHeader() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("oidc-user");
    when(apiKeyService.getKey("oidc-user", Provider.OPENROUTER)).thenReturn(Optional.of("or-oidc-key"));
    when(modelCatalogService.listOpenRouterModels("or-oidc-key")).thenReturn(models);
    when(entitlementService.filterOpenRouterModelsForPlan("oidc-user", models))
        .thenReturn(models);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(apiKeyService).getKey("oidc-user", Provider.OPENROUTER);
    verify(modelCatalogService).listOpenRouterModels("or-oidc-key");
  }

  @Test
  void openRouterModels_appliesFreeTierFilter() {
    List<ModelCatalogProperties.ModelOption> all =
        List.of(
            new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"),
            new ModelCatalogProperties.ModelOption(
                "meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 (free)"));
    List<ModelCatalogProperties.ModelOption> onlyFree =
        List.of(
            new ModelCatalogProperties.ModelOption(
                "meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 (free)"));
    when(principal.getSubject()).thenReturn("free-user");
    when(apiKeyService.getKey("free-user", Provider.OPENROUTER)).thenReturn(Optional.empty());
    // No managed key configured → catalog is fetched with null (YAML fallback path).
    when(modelCatalogService.listOpenRouterModels(null)).thenReturn(all);
    when(entitlementService.filterOpenRouterModelsForPlan("free-user", all)).thenReturn(onlyFree);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(onlyFree);
  }

  @Test
  void openRouterModels_fallsBackToManagedKey_whenUserHasNoByokKey() {
    // Free-tier users typically don't have a BYOK key. The controller must fall back to the
    // server-managed OpenRouter key so the catalog is fetched live (and actually contains
    // `:free` ids) rather than returning the empty YAML fallback after filtering.
    List<ModelCatalogProperties.ModelOption> live =
        List.of(
            new ModelCatalogProperties.ModelOption(
                "meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 (free)"));
    managedApiKeyProperties.setOpenrouter("srv-openrouter");
    when(principal.getSubject()).thenReturn("free-user");
    when(apiKeyService.getKey("free-user", Provider.OPENROUTER)).thenReturn(Optional.empty());
    when(modelCatalogService.listOpenRouterModels("srv-openrouter")).thenReturn(live);
    when(entitlementService.filterOpenRouterModelsForPlan("free-user", live)).thenReturn(live);

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
    when(entitlementService.filterOpenRouterModelsForPlan("premium-user", models))
        .thenReturn(models);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(modelCatalogService).listOpenRouterModels("user-key");
  }
}
