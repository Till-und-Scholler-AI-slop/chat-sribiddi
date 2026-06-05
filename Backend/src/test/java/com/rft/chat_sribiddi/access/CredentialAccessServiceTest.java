package com.rft.chat_sribiddi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class CredentialAccessServiceTest {

  private static final String USER_ID = "user-123";

  @Mock private ApiKeyService apiKeyService;

  @Test
  void resolveUserCredential_withStoredKey_returnsUserKeySource() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("sk-user"));
    CredentialAccessService service = newService(new MockEnvironment());

    var resolved = service.resolveUserCredential(USER_ID, Provider.OPENAI, "gpt-5.5");

    assertThat(resolved.apiKey()).isEqualTo("sk-user");
    assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.USER_KEY);
  }

  @Test
  void resolveUserCredential_withoutStoredKey_throwsApiKeyRequired() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.empty());
    CredentialAccessService service = newService(new MockEnvironment());

    assertThatThrownBy(() -> service.resolveUserCredential(USER_ID, Provider.OPENAI, "gpt-5.5"))
        .isInstanceOfSatisfying(
            AccessRestrictionException.class,
            ex -> assertThat(ex.getReason()).isEqualTo(AccessRestrictionException.Reason.API_KEY_REQUIRED));
  }

  @Test
  void resolveGuestOpenRouterFree_withServerKey_returnsGuestSource() {
    CredentialAccessService service =
        newService(new MockEnvironment().withProperty("OPENROUTER_API_KEY", "or-server"));

    var resolved = service.resolveGuestOpenRouterFree(CredentialAccessService.OPENROUTER_FREE_MODEL_ID);

    assertThat(resolved.apiKey()).isEqualTo("or-server");
    assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.GUEST_OPENROUTER_FREE);
  }

  @Test
  void resolveGuestOpenRouterFree_rejectsOtherModel() {
    CredentialAccessService service =
        newService(new MockEnvironment().withProperty("OPENROUTER_API_KEY", "or-server"));

    assertThatThrownBy(() -> service.resolveGuestOpenRouterFree("openrouter/auto"))
        .isInstanceOfSatisfying(
            AccessRestrictionException.class,
            ex -> assertThat(ex.getReason()).isEqualTo(AccessRestrictionException.Reason.MODEL_NOT_ALLOWED));
  }

  @Test
  void resolveGuestOpenRouterFree_withoutServerKey_returnsManagedUnconfigured() {
    CredentialAccessService service = newService(new MockEnvironment());

    assertThatThrownBy(
            () -> service.resolveGuestOpenRouterFree(CredentialAccessService.OPENROUTER_FREE_MODEL_ID))
        .isInstanceOfSatisfying(
            AccessRestrictionException.class,
            ex ->
                assertThat(ex.getReason())
                    .isEqualTo(AccessRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED));
  }

  @Test
  void annotateModels_marksOnlyGuestOpenRouterFreeAlwaysWithoutKey() {
    CredentialAccessService service = newService(new MockEnvironment());
    List<ModelOption> models =
        List.of(
            new ModelOption(CredentialAccessService.OPENROUTER_FREE_MODEL_ID, "Free"),
            new ModelOption("openrouter/auto", "Auto"));

    List<ModelOption> annotated = service.annotateModels(null, Provider.OPENROUTER, models, false);

    assertThat(annotated.get(0).getAvailability()).isEqualTo(AvailabilityFlag.ALWAYS);
    assertThat(annotated.get(1).getAvailability()).isEqualTo(AvailabilityFlag.BYOK_ONLY);
  }

  @Test
  void annotateModels_marksSignedInModelsByokOnlyWithoutKey() {
    CredentialAccessService service = newService(new MockEnvironment());
    List<ModelOption> models =
        List.of(new ModelOption(CredentialAccessService.OPENROUTER_FREE_MODEL_ID, "Free"));

    List<ModelOption> annotated = service.annotateModels(USER_ID, Provider.OPENROUTER, models, false);

    assertThat(annotated.getFirst().getAvailability()).isEqualTo(AvailabilityFlag.BYOK_ONLY);
  }

  private CredentialAccessService newService(MockEnvironment environment) {
    return new CredentialAccessService(apiKeyService, new AccessProperties(), environment);
  }
}
