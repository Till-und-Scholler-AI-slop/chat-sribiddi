package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
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
  @Mock private HttpServletRequest request;
  @Mock private OidcUser principal;

  private ModelController controller;

  @BeforeEach
  void setUp() {
    controller =
        new ModelController(modelCatalogService, apiKeyService, new RequestUserResolver(new MockEnvironment()));
  }

  @Test
  void openRouterModels_returnsConfiguredModelsWhenPrincipalMissing() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openrouter/auto", "OpenRouter Auto"));
    when(modelCatalogService.listOpenRouterModels(null)).thenReturn(models);

    var response = controller.openRouterModels(null, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(modelCatalogService).listOpenRouterModels(null);
  }

  @Test
  void openRouterModels_prefersAuthenticatedUserOverRequestHeader() {
    List<ModelCatalogProperties.ModelOption> models =
        List.of(new ModelCatalogProperties.ModelOption("openai/gpt-4o", "GPT-4o"));
    when(principal.getSubject()).thenReturn("oidc-user");
    when(apiKeyService.getKey("oidc-user", Provider.OPENROUTER)).thenReturn(Optional.of("or-oidc-key"));
    when(modelCatalogService.listOpenRouterModels("or-oidc-key")).thenReturn(models);

    var response = controller.openRouterModels(principal, request);

    assertThat(response.getBody()).isEqualTo(models);
    verify(apiKeyService).getKey("oidc-user", Provider.OPENROUTER);
    verify(modelCatalogService).listOpenRouterModels("or-oidc-key");
  }
}
