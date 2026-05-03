package com.rft.chat_sribiddi.keyring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ApiKeyControllerTest {

  @Mock private ApiKeyService apiKeyService;
  @Mock private HttpServletRequest request;
  @Mock private OidcUser principal;

  private ApiKeyController controller;

  @BeforeEach
  void setUp() {
    controller = new ApiKeyController(apiKeyService, new RequestUserResolver(new MockEnvironment()));
  }

  @Test
  void upsertApiKey_usesAuthenticatedUserId() {
    when(principal.getSubject()).thenReturn("oidc-user");

    controller.upsertApiKey(
        principal, request, "openrouter", new ApiKeyController.ApiKeyPayload("sk-or-user"));

    verify(apiKeyService).saveKey("oidc-user", com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider.OPENROUTER, "sk-or-user");
  }

  @Test
  void hasApiKey_requiresAnyUserIdentity() {
    assertThatThrownBy(() -> controller.hasApiKey(null, request, "openrouter"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401 UNAUTHORIZED");
  }
}
