package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class BillingModeResolverTest {

  private static final String USER_ID = "user-123";
  private static final String MODEL_ID = "gpt-4o-mini";

  @Mock private ApiKeyService apiKeyService;
  @Mock private EntitlementService entitlementService;

  private BillingModeResolver newResolver(
      String openAiServerKey,
      String googleServerKey,
      String claudeServerKey,
      String openRouterServerKey) {
    ManagedApiKeyProperties props = new ManagedApiKeyProperties();
    props.setOpenai(openAiServerKey);
    props.setGoogle(googleServerKey);
    props.setClaude(claudeServerKey);
    props.setOpenrouter(openRouterServerKey);
    return new BillingModeResolver(apiKeyService, entitlementService, props, new MockEnvironment());
  }

  private BillingModeResolver newResolverWithAllServerKeys() {
    return newResolver("srv-openai", "srv-google", "srv-claude", "srv-openrouter");
  }

  private BillingModeResolver newResolverWithEnvironmentFallback(
      String openRouterServerKey, String openRouterEnvKey) {
    ManagedApiKeyProperties props = new ManagedApiKeyProperties();
    props.setOpenrouter(openRouterServerKey);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("OPENROUTER_API_KEY", openRouterEnvKey);
    return new BillingModeResolver(apiKeyService, entitlementService, props, environment);
  }

  // ---------- BYOK ----------

  @Test
  void byok_withStoredUserKey_returnsByokResolvedKey() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.BYOK);

    assertThat(resolved.apiKey()).isEqualTo("user-key");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.BYOK);
  }

  @Test
  void byok_withStoredUserKey_allowsPaidModelOnFreePlan() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.BYOK);

    assertThat(resolved.apiKey()).isEqualTo("user-key");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.BYOK);
  }

  @Test
  void byok_withoutStoredUserKey_throwsPlanRestriction() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.empty());
    when(entitlementService.get(USER_ID))
        .thenReturn(entitlementsOnPlan(Plan.FREE));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(
            () -> resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.BYOK))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex -> {
              PlanRestrictionException pre = (PlanRestrictionException) ex;
              assertThat(pre.getReason())
                  .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED);
              assertThat(pre.getPlan()).isEqualTo(Plan.FREE);
            });
  }

  // ---------- AUTO ----------

  @Test
  void auto_withUserKey_prefersByok() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.AUTO);

    assertThat(resolved.apiKey()).isEqualTo("user-key");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.BYOK);
  }

  @Test
  void auto_withUserKey_allowsPaidModelOnFreePlan() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.AUTO);

    assertThat(resolved.apiKey()).isEqualTo("user-key");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.BYOK);
  }

  @Test
  void auto_withoutUserKey_fallsBackToManagedWhenAllowed() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.empty());
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.AUTO);

    assertThat(resolved.apiKey()).isEqualTo("srv-openai");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);
  }

  @Test
  void auto_withoutUserKey_managedBlockedByPlan_throwsPlanRestriction() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.empty());
    PlanRestrictionException denied =
        new PlanRestrictionException(
            PlanRestrictionException.Reason.MODEL_NOT_ALLOWED, Plan.FREE, "nope");
    doThrow(denied)
        .when(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(
            () -> resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.AUTO))
        .isSameAs(denied);
  }

  @Test
  void nullRequestedMode_isTreatedAsAuto() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, null);

    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
  }

  // ---------- MANAGED ----------

  @Test
  void managed_withServerKey_returnsManagedResolvedKey() {
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey openai =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);
    BillingModeResolver.ResolvedKey google =
        resolver.resolve(USER_ID, Provider.GOOGLE, MODEL_ID, BillingMode.MANAGED);
    BillingModeResolver.ResolvedKey claude =
        resolver.resolve(USER_ID, Provider.CLAUDE, MODEL_ID, BillingMode.MANAGED);
    BillingModeResolver.ResolvedKey openrouter =
        resolver.resolve(USER_ID, Provider.OPENROUTER, MODEL_ID, BillingMode.MANAGED);

    assertThat(openai.apiKey()).isEqualTo("srv-openai");
    assertThat(openai.effectiveMode()).isEqualTo(BillingMode.MANAGED);
    assertThat(google.apiKey()).isEqualTo("srv-google");
    assertThat(claude.apiKey()).isEqualTo("srv-claude");
    assertThat(openrouter.apiKey()).isEqualTo("srv-openrouter");
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.GOOGLE, MODEL_ID, BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.CLAUDE, MODEL_ID, BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENROUTER, MODEL_ID, BillingMode.MANAGED);
  }

  @Test
  void managed_withStoredUserKey_stillPrefersByok() {
    when(apiKeyService.getKey(USER_ID, Provider.OPENAI)).thenReturn(Optional.of("user-key"));
    BillingModeResolver resolver = newResolver("", "srv-google", "srv-claude", "srv-openrouter");

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);

    assertThat(resolved.apiKey()).isEqualTo("user-key");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.BYOK);
    verify(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.BYOK);
  }

  @Test
  void managed_blockedByEntitlement_throwsPlanRestriction() {
    PlanRestrictionException denied =
        new PlanRestrictionException(
            PlanRestrictionException.Reason.MODEL_NOT_ALLOWED, Plan.FREE, "nope");
    doThrow(denied)
        .when(entitlementService)
        .assertCanUseModel(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED);
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(
            () -> resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED))
        .isSameAs(denied);
  }

  @Test
  void managed_missingServerKey_throwsPlanRestriction() {
    when(entitlementService.get(USER_ID)).thenReturn(entitlementsOnPlan(Plan.FREE));
    // Entitlement check passes (does nothing), but server key is blank.
    BillingModeResolver resolver = newResolver("", "srv-google", "srv-claude", "srv-openrouter");

    assertThatThrownBy(
            () -> resolver.resolve(USER_ID, Provider.OPENAI, MODEL_ID, BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .hasMessageContaining("server key missing")
        .satisfies(
            ex -> {
              PlanRestrictionException pre = (PlanRestrictionException) ex;
              assertThat(pre.getReason())
                  .isEqualTo(PlanRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED);
              assertThat(pre.getPlan()).isEqualTo(Plan.FREE);
            });
  }

  @Test
  void managed_anonymousOpenRouterFreeWithServerKey_returnsManagedResolvedKey() {
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(
            null,
            Provider.OPENROUTER,
            ModelPricingLookup.OPENROUTER_FREE_MODEL_ID,
            BillingMode.MANAGED);

    assertThat(resolved.apiKey()).isEqualTo("srv-openrouter");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(
            null,
            Provider.OPENROUTER,
            ModelPricingLookup.OPENROUTER_FREE_MODEL_ID,
            BillingMode.MANAGED);
    verify(apiKeyService, never()).getKey(any(), any());
  }

  @Test
  void managed_anonymousOpenRouterFreeWithBlankManagedProperty_usesOpenRouterEnvFallback() {
    BillingModeResolver resolver = newResolverWithEnvironmentFallback("", "env-openrouter");

    BillingModeResolver.ResolvedKey resolved =
        resolver.resolve(
            null,
            Provider.OPENROUTER,
            ModelPricingLookup.OPENROUTER_FREE_MODEL_ID,
            BillingMode.MANAGED);

    assertThat(resolved.apiKey()).isEqualTo("env-openrouter");
    assertThat(resolved.effectiveMode()).isEqualTo(BillingMode.MANAGED);
    verify(entitlementService)
        .assertCanUseModel(
            null,
            Provider.OPENROUTER,
            ModelPricingLookup.OPENROUTER_FREE_MODEL_ID,
            BillingMode.MANAGED);
  }

  @Test
  void managed_braveSearch_isRejected() {
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(
            () ->
                resolver.resolve(
                    USER_ID, Provider.BRAVE_SEARCH, MODEL_ID, BillingMode.MANAGED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BRAVE_SEARCH");
  }

  // ---------- Misc ----------

  @Test
  void anonymousUser_byok_throwsPlanRestriction() {
    when(entitlementService.get(null)).thenReturn(entitlementsOnPlan(Plan.FREE));
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(
            () -> resolver.resolve(null, Provider.OPENAI, MODEL_ID, BillingMode.BYOK))
        .isInstanceOf(PlanRestrictionException.class);
    verify(apiKeyService, never()).getKey(any(), any());
  }

  @Test
  void nullProvider_throws() {
    BillingModeResolver resolver = newResolverWithAllServerKeys();

    assertThatThrownBy(() -> resolver.resolve(USER_ID, null, MODEL_ID, BillingMode.BYOK))
        .isInstanceOf(NullPointerException.class);
  }

  private Entitlements entitlementsOnPlan(Plan plan) {
    return new Entitlements(
        plan, 1, 1L, 1L, 1, java.util.Set.of(), 0L, 1, null, null);
  }
}
