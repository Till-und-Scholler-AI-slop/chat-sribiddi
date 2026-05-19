package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.billing.BillingProperties.ModelAccess;
import com.rft.chat_sribiddi.billing.BillingProperties.TierConfig;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

  private static final String USER_ID = "provider-user-1";
  private static final long INTERNAL_ID = 42L;
  private static final LocalDate TODAY = LocalDate.of(2026, 4, 19);

  @Mock private UserRepository userRepository;
  @Mock private DailyQuotaCounterRepository dailyQuotaCounterRepository;
  @Mock private ModelPricingLookup modelPricingLookup;

  private BillingProperties billingProperties;
  private EntitlementService service;

  @BeforeEach
  void setUp() {
    billingProperties = new BillingProperties();
    Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
    tiers.put(Plan.FREE, tier(Set.of(), 2, 10L));
    tiers.put(Plan.PREMIUM, tier(Set.of("brave-search"), 5, 100L));
    tiers.put(Plan.MAX, tier(Set.of("*"), 10, 0L));
    billingProperties.setTiers(tiers);

    ModelAccess access = new ModelAccess();
    access.setFreeMaxInputUsdPerMillion(0.0);
    access.setFreeMaxOutputUsdPerMillion(0.0);
    access.setManagedProviders(new LinkedHashSet<>(Set.of(Provider.OPENROUTER)));
    billingProperties.setModelAccess(access);

    Clock fixed = Clock.fixed(Instant.parse("2026-04-19T10:15:30Z"), ZoneId.of("UTC"));
    service =
        new EntitlementService(
            billingProperties, userRepository, dailyQuotaCounterRepository, modelPricingLookup,
            fixed);
  }

  private TierConfig tier(Set<String> tools, int maxAttachments, long dailyCap) {
    TierConfig t = new TierConfig();
    t.setTools(new LinkedHashSet<>(tools));
    t.setMaxAttachments(maxAttachments);
    t.setMaxAttachmentBytes(1_000L);
    t.setMaxTotalAttachmentBytes(2_000L);
    t.setContextMessageLimit(15);
    t.setDailyCap(dailyCap);
    t.setParallelStreams(1);
    return t;
  }

  private User userWithPlan(Plan plan) {
    User u = new User();
    u.setId(INTERNAL_ID);
    u.setProviderId(USER_ID);
    u.setPlan(plan);
    return u;
  }

  // -------- get() --------

  @Test
  void get_returnsFreeTierEntitlementsForFreeUser() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));

    Entitlements entitlements = service.get(USER_ID);

    assertThat(entitlements.plan()).isEqualTo(Plan.FREE);
    assertThat(entitlements.allModelsAllowed()).isFalse();
    assertThat(entitlements.maxModelInputUsdPerMillion()).isEqualTo(0.0);
    assertThat(entitlements.maxModelOutputUsdPerMillion()).isEqualTo(0.0);
    assertThat(entitlements.maxAttachments()).isEqualTo(2);
    assertThat(entitlements.dailyCap()).isEqualTo(10L);
    assertThat(entitlements.allowedTools()).isEmpty();
  }

  @Test
  void get_returnsPremiumTierWithPriceCap() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));

    Entitlements entitlements = service.get(USER_ID);

    assertThat(entitlements.plan()).isEqualTo(Plan.PREMIUM);
    // BILL-8: Premium is capped at the configured premium thresholds.
    assertThat(entitlements.allModelsAllowed()).isFalse();
    assertThat(entitlements.maxModelInputUsdPerMillion())
        .isEqualTo(billingProperties.getModelAccess().getPremiumMaxInputUsdPerMillion());
    assertThat(entitlements.maxModelOutputUsdPerMillion())
        .isEqualTo(billingProperties.getModelAccess().getPremiumMaxOutputUsdPerMillion());
    assertThat(entitlements.allowsTool("brave-search")).isTrue();
    assertThat(entitlements.allowsTool("other-tool")).isFalse();
  }

  @Test
  void get_returnsMaxTierWithAllTools() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));

    Entitlements entitlements = service.get(USER_ID);

    assertThat(entitlements.plan()).isEqualTo(Plan.MAX);
    assertThat(entitlements.allModelsAllowed()).isTrue();
    assertThat(entitlements.allowsTool("brave-search")).isTrue();
    assertThat(entitlements.allowsTool("anything")).isTrue();
    assertThat(entitlements.dailyCap()).isZero();
  }

  @Test
  void get_fallsBackToFreeTierWhenUserMissing() {
    when(userRepository.findByProviderId("unknown")).thenReturn(Optional.empty());

    Entitlements entitlements = service.get("unknown");

    assertThat(entitlements.plan()).isEqualTo(Plan.FREE);
    assertThat(entitlements.allModelsAllowed()).isFalse();
  }

  // -------- resolveBillingMode --------

  @Test
  void resolveBillingMode_defaultsToAutoWhenUserMissing() {
    when(userRepository.findByProviderId("unknown")).thenReturn(Optional.empty());

    assertThat(service.resolveBillingMode("unknown")).isEqualTo(BillingMode.AUTO);
  }

  @Test
  void resolveBillingMode_preservesAutoPreference() {
    User user = userWithPlan(Plan.FREE);
    user.setBillingModePreference(BillingMode.AUTO);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));

    assertThat(service.resolveBillingMode(USER_ID)).isEqualTo(BillingMode.AUTO);
  }

  @Test
  void resolveBillingMode_returnsExplicitPreference() {
    User user = userWithPlan(Plan.FREE);
    user.setBillingModePreference(BillingMode.MANAGED);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));

    assertThat(service.resolveBillingMode(USER_ID)).isEqualTo(BillingMode.MANAGED);
  }

  // -------- assertCanUseModel --------

  @Test
  void assertCanUseModel_managedMode_allowsOpenRouterFreeRouterForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openrouter/free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));

    service.assertCanUseModel(
        USER_ID,
        Provider.OPENROUTER,
        "openrouter/free",
        BillingMode.MANAGED);
  }

  @Test
  void assertCanUseModel_managedMode_rejectsIndividualFreeModelForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID,
                    Provider.OPENROUTER,
                    "meta-llama/llama-3.1-8b-instruct:free",
                    BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex -> {
              PlanRestrictionException pre = (PlanRestrictionException) ex;
              assertThat(pre.getReason())
                  .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED);
              assertThat(pre.getPlan()).isEqualTo(Plan.FREE);
            });
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_managedMode_rejectsPaidModelForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID, Provider.OPENROUTER, "openai/gpt-5.2", BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex -> {
              PlanRestrictionException pre = (PlanRestrictionException) ex;
              assertThat(pre.getReason())
                  .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED);
              assertThat(pre.getPlan()).isEqualTo(Plan.FREE);
            });
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_managedMode_rejectsUnknownPricingForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID, Provider.OPENROUTER, "some/unknown-model", BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex ->
                assertThat(((PlanRestrictionException) ex).getReason())
                    .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED));
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_managedMode_allowsCheapModelForPremium() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o-mini"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.15, 0.60)));

    service.assertCanUseModel(
        USER_ID, Provider.OPENROUTER, "openai/gpt-4o-mini", BillingMode.MANAGED);
  }

  @Test
  void assertCanUseModel_rejectsExpensivePremiumModelForPremiumPlan() {
    // BILL-8: Premium is capped at 5.0/5.0 USD per 1M; claude-opus-4-7 at $15/$75 exceeds it
    // and must be rejected with MODEL_NOT_ALLOWED (→ HTTP 402, upgrade-to-MAX hint).
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "anthropic/claude-opus-4-7"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(15.0, 75.0)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID,
                    Provider.OPENROUTER,
                    "anthropic/claude-opus-4-7",
                    BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex -> {
              PlanRestrictionException pre = (PlanRestrictionException) ex;
              assertThat(pre.getReason())
                  .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED);
              assertThat(pre.getPlan()).isEqualTo(Plan.PREMIUM);
            });
  }

  @Test
  void assertCanUseModel_managedMode_allowsAnyOpenRouterModelForMax() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));

    service.assertCanUseModel(
        USER_ID, Provider.OPENROUTER, "any-future-model", BillingMode.MANAGED);

    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_rejectsBlankModelId() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));

    assertThatThrownBy(() -> service.assertCanUseModel(USER_ID, Provider.OPENAI, " "))
        .isInstanceOf(PlanRestrictionException.class);
  }

  // -------- managed-mode provider gating --------

  @Test
  void assertCanUseModel_managedMode_rejectsDirectOpenAiForPremium() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex ->
                assertThat(((PlanRestrictionException) ex).getReason())
                    .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED));
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_managedMode_rejectsDirectClaudeForMax() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID, Provider.CLAUDE, "claude-sonnet-4-6", BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class);
  }

  @Test
  void assertCanUseModel_managedMode_allowsOpenRouterForPremium() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o-mini"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.15, 0.60)));

    service.assertCanUseModel(
        USER_ID, Provider.OPENROUTER, "openai/gpt-4o-mini", BillingMode.MANAGED);
  }

  @Test
  void assertCanUseModel_byokMode_allowsDirectOpenAiWithoutPlanPricing() {
    service.assertCanUseModel(USER_ID, Provider.OPENAI, "gpt-5.2", BillingMode.BYOK);

    verifyNoInteractions(userRepository);
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void assertCanUseModel_managedMode_freeUserCannotBypassFreeRouter() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));

    assertThatThrownBy(
            () ->
                service.assertCanUseModel(
                    USER_ID, Provider.OPENROUTER, "openai/gpt-4o-mini", BillingMode.MANAGED))
        .isInstanceOf(PlanRestrictionException.class);
    verifyNoInteractions(modelPricingLookup);
  }

  // -------- assertWithinDailyCap --------

  @Test
  void assertWithinDailyCap_allowsWhenUnderCapForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(dailyQuotaCounterRepository.getCount(INTERNAL_ID, TODAY)).thenReturn(5L);

    service.assertWithinDailyCap(USER_ID);
  }

  @Test
  void assertWithinDailyCap_rejectsWhenAtCapForFree() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(dailyQuotaCounterRepository.getCount(INTERNAL_ID, TODAY)).thenReturn(10L);

    assertThatThrownBy(() -> service.assertWithinDailyCap(USER_ID))
        .isInstanceOf(PlanRestrictionException.class)
        .satisfies(
            ex ->
                assertThat(((PlanRestrictionException) ex).getReason())
                    .isEqualTo(PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED));
  }

  @Test
  void assertWithinDailyCap_noOpWhenCapIsZero() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));

    service.assertWithinDailyCap(USER_ID);

    verifyNoInteractions(dailyQuotaCounterRepository);
  }

  // -------- incrementCounter --------

  @Test
  void incrementCounter_callsRepositoryWithTodayAndInternalId() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));

    service.incrementCounter(USER_ID);

    verify(dailyQuotaCounterRepository).incrementCounter(eq(INTERNAL_ID), eq(TODAY));
  }

  @Test
  void incrementCounter_skipsWhenUserMissing() {
    when(userRepository.findByProviderId("unknown")).thenReturn(Optional.empty());

    service.incrementCounter("unknown");

    verify(dailyQuotaCounterRepository, never()).incrementCounter(anyLong(), any());
  }

  // -------- annotateModelsForPlan (BILL-15: snapshot-per-tier) --------

  @Test
  void annotateModelsForPlan_withUserKey_marksEveryModelAlways() {
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-5.2", "GPT-5.2");
    var unknown =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "future/model", "Future Model");

    var result =
        service.annotateModelsForPlan(
            USER_ID, Provider.OPENROUTER, java.util.List.of(paid, unknown), true);

    assertThat(result)
        .extracting(o -> o.getId() + ":" + o.getAvailability() + ":" + o.getRequiredPlan())
        .containsExactly("openai/gpt-5.2:ALWAYS:null", "future/model:ALWAYS:null");
    verifyNoInteractions(userRepository);
    verifyNoInteractions(modelPricingLookup);
  }

  @Test
  void annotateModelsForPlan_openRouter_freeTier_allowsOnlyOpenRouterFreeRouter() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openrouter/free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "meta-llama/llama-3.1-8b-instruct:free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o")).thenReturn(Optional.empty());
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var freeRouter =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openrouter/free", "OpenRouter Free");
    var individualFree =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "meta-llama/llama-3.1-8b-instruct:free", "Llama (free)");

    var result =
        service.annotateModelsForPlan(
            USER_ID, Provider.OPENROUTER, java.util.List.of(paid, freeRouter, individualFree));

    // Snapshot: FREE managed users may select only the OpenRouter free router model.
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getId()).isEqualTo("openai/gpt-4o");
    assertThat(result.get(0).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.UPGRADE_REQUIRED);
    assertThat(result.get(0).getRequiredPlan()).isEqualTo(Plan.MAX);
    assertThat(result.get(1).getId()).isEqualTo("openrouter/free");
    assertThat(result.get(1).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.ALWAYS);
    assertThat(result.get(1).getRequiredPlan()).isNull();
    assertThat(result.get(2).getId()).isEqualTo("meta-llama/llama-3.1-8b-instruct:free");
    assertThat(result.get(2).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.UPGRADE_REQUIRED);
    assertThat(result.get(2).getRequiredPlan()).isEqualTo(Plan.PREMIUM);
  }

  @Test
  void annotateModelsForPlan_openRouter_freeTier_keepsRouterGateWhenThresholdsAreRaised() {
    // Even if the configured Free price threshold is raised, Free managed mode still uses the
    // OpenRouter free router model as the only selectable option.
    billingProperties.getModelAccess().setFreeMaxInputUsdPerMillion(1.0);
    billingProperties.getModelAccess().setFreeMaxOutputUsdPerMillion(1.0);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openrouter/free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "cheap/model"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.5, 0.5)));
    var freeRouter =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openrouter/free", "OpenRouter Free");
    var cheap =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "cheap/model", "Cheap");

    var result =
        service.annotateModelsForPlan(
            USER_ID, Provider.OPENROUTER, java.util.List.of(freeRouter, cheap));

    assertThat(result.get(0).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.ALWAYS);
    assertThat(result.get(1).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.UPGRADE_REQUIRED);
    assertThat(result.get(1).getRequiredPlan()).isEqualTo(Plan.PREMIUM);
  }

  @Test
  void annotateModelsForPlan_openRouter_premiumTier_allowsModelsUnderPremiumCap() {
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));
    // BILL-8: Premium is capped at 5.0/5.0; both sample models are under that.
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(2.5, 4.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "meta-llama/llama-3.1-8b-instruct:free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var free =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "meta-llama/llama-3.1-8b-instruct:free", "Llama (free)");

    var result =
        service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, java.util.List.of(paid, free));

    // Models under the Premium cap are ALWAYS available with no upgrade required.
    assertThat(result)
        .extracting(o -> o.getId() + ":" + o.getAvailability() + ":" + o.getRequiredPlan())
        .containsExactly(
            "openai/gpt-4o:ALWAYS:null",
            "meta-llama/llama-3.1-8b-instruct:free:ALWAYS:null");
  }

  @Test
  void annotateModelsForPlan_openRouter_premiumTier_flagsExpensiveModelsAsMaxUpgrade() {
    // BILL-8: claude-opus-4-7 above the $5/1M Premium cap → UPGRADE_REQUIRED to MAX.
    when(userRepository.findByProviderId(USER_ID))
        .thenReturn(Optional.of(userWithPlan(Plan.PREMIUM)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "anthropic/claude-opus-4-7"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(15.0, 75.0)));
    var opus =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "anthropic/claude-opus-4-7", "Claude Opus 4.7");

    var result =
        service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, java.util.List.of(opus));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.UPGRADE_REQUIRED);
    assertThat(result.get(0).getRequiredPlan()).isEqualTo(Plan.MAX);
  }

  @Test
  void annotateModelsForPlan_openRouter_freeTier_flagsExpensiveModelsAsMaxUpgrade() {
    // BILL-8: a FREE user looking at an expensive model (> Premium cap) should be pushed all
    // the way to MAX, not just PREMIUM — Premium couldn't use it either.
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "anthropic/claude-opus-4-7"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(15.0, 75.0)));
    var opus =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "anthropic/claude-opus-4-7", "Claude Opus 4.7");

    var result =
        service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, java.util.List.of(opus));

    assertThat(result.get(0).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.UPGRADE_REQUIRED);
    assertThat(result.get(0).getRequiredPlan()).isEqualTo(Plan.MAX);
  }

  @Test
  void annotateModelsForPlan_openRouter_maxTier_allModelsAlwaysAvailable() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var free =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "meta-llama/llama-3.1-8b-instruct:free", "Llama (free)");

    var result =
        service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, java.util.List.of(paid, free));

    // Snapshot: MAX user has no upgrade gate either.
    assertThat(result)
        .extracting(o -> o.getId() + ":" + o.getAvailability() + ":" + o.getRequiredPlan())
        .containsExactly(
            "openai/gpt-4o:ALWAYS:null",
            "meta-llama/llama-3.1-8b-instruct:free:ALWAYS:null");
  }

  @Test
  void annotateModelsForPlan_directProviders_areByokOnlyForEveryTier() {
    // Direct providers (OpenAI/Google/Claude) are BYOK-only because managed mode only routes
    // through OpenRouter (see app.billing.model-access.managed-providers).
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    var gpt =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "gpt-4o", "GPT-4o");

    var result = service.annotateModelsForPlan(USER_ID, Provider.OPENAI, java.util.List.of(gpt));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.BYOK_ONLY);
    assertThat(result.get(0).getRequiredPlan()).isNull();
  }

  @Test
  void annotateModelsForPlan_doesNotMutateInputList() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var input = java.util.List.of(paid);

    service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, input);

    // Cached catalog entries must not be mutated — only the returned copies carry flags.
    assertThat(paid.getAvailability()).isNull();
    assertThat(paid.getRequiredPlan()).isNull();
  }

  @Test
  void annotateModelsForPlan_handlesNullAndEmptyInput() {
    assertThat(service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, null)).isEmpty();
    assertThat(service.annotateModelsForPlan(USER_ID, Provider.OPENROUTER, java.util.List.of()))
        .isEmpty();
    verifyNoInteractions(userRepository);
  }

  // -------- filterUsableModelsForPlan --------

  @Test
  void filterUsableModelsForPlan_openRouterFreeTier_returnsOnlyFreeRouterModel() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openrouter/free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "meta-llama/llama-3.1-8b-instruct:free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(2.5, 10.0)));
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var freeRouter =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openrouter/free", "OpenRouter Free");
    var individualFree =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "meta-llama/llama-3.1-8b-instruct:free", "Llama (free)");

    var result =
        service.filterUsableModelsForPlan(
            USER_ID, Provider.OPENROUTER, java.util.List.of(paid, freeRouter, individualFree), false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getId()).isEqualTo("openrouter/free");
    assertThat(result.getFirst().getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.ALWAYS);
  }

  @Test
  void filterUsableModelsForPlan_directProviderWithoutUserKey_returnsEmptyList() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.MAX)));
    var gpt =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "gpt-4o", "GPT-4o");

    var result =
        service.filterUsableModelsForPlan(
            USER_ID, Provider.OPENAI, java.util.List.of(gpt), false);

    assertThat(result).isEmpty();
  }

  @Test
  void filterUsableModelsForPlan_withUserKey_returnsAllProviderModels() {
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "gpt-5.2", "GPT-5.2");
    var future =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "future-model", "Future Model");

    var result =
        service.filterUsableModelsForPlan(
            USER_ID, Provider.OPENAI, java.util.List.of(paid, future), true);

    assertThat(result)
        .extracting(o -> o.getId() + ":" + o.getAvailability() + ":" + o.getRequiredPlan())
        .containsExactly("gpt-5.2:ALWAYS:null", "future-model:ALWAYS:null");
    verifyNoInteractions(userRepository);
    verifyNoInteractions(modelPricingLookup);
  }

  // -------- filterOpenRouterModelsForPlan (deprecated shim) -----

  @Test
  void filterOpenRouterModelsForPlan_delegatesToUsableFilterForBackwardsCompatibility() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(userWithPlan(Plan.FREE)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openrouter/free"))
        .thenReturn(Optional.of(new ModelPricingLookup.Pricing(0.0, 0.0)));
    when(modelPricingLookup.find(Provider.OPENROUTER, "openai/gpt-4o")).thenReturn(Optional.empty());
    var paid =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openai/gpt-4o", "GPT-4o");
    var free =
        new com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption(
            "openrouter/free", "OpenRouter Free");

    var result = service.filterOpenRouterModelsForPlan(USER_ID, java.util.List.of(paid, free));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getId()).isEqualTo("openrouter/free");
    assertThat(result.getFirst().getAvailability())
        .isEqualTo(com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag.ALWAYS);
  }
}
