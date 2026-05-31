package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.billing.BillingProperties.ModelAccess;
import com.rft.chat_sribiddi.billing.BillingProperties.TierConfig;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Central plan-gating and quota service. Resolves a user's current {@link Plan} and returns their
 * effective {@link Entitlements}, and enforces per-request plan restrictions (price-gated model
 * access, tools, daily-cap).
 *
 * <p>Configuration is sourced from {@link BillingProperties} ({@code app.billing.*}) — no limits
 * are hardcoded here. Model access is not whitelisted by ID; instead the Free tier applies the
 * price thresholds configured under {@code app.billing.model-access.*}; Free managed-mode calls
 * are additionally restricted to OpenRouter's {@code openrouter/free} router model.
 *
 * <p>The {@code userId} parameter is the OAuth provider id (see {@link
 * com.rft.chat_sribiddi.auth.RequestUserResolver}), matching the convention used by the rest of
 * the application.
 */
@Slf4j
@Service
public class EntitlementService {

  private final BillingProperties billingProperties;
  private final UserRepository userRepository;
  private final DailyQuotaCounterRepository dailyQuotaCounterRepository;
  private final ModelPricingLookup modelPricingLookup;
  private final Clock clock;

  @Autowired
  public EntitlementService(
      BillingProperties billingProperties,
      UserRepository userRepository,
      DailyQuotaCounterRepository dailyQuotaCounterRepository,
      ModelPricingLookup modelPricingLookup) {
    this(
        billingProperties,
        userRepository,
        dailyQuotaCounterRepository,
        modelPricingLookup,
        Clock.system(ZoneId.of("UTC")));
  }

  EntitlementService(
      BillingProperties billingProperties,
      UserRepository userRepository,
      DailyQuotaCounterRepository dailyQuotaCounterRepository,
      ModelPricingLookup modelPricingLookup,
      Clock clock) {
    this.billingProperties = billingProperties;
    this.userRepository = userRepository;
    this.dailyQuotaCounterRepository = dailyQuotaCounterRepository;
    this.modelPricingLookup = modelPricingLookup;
    this.clock = clock;
  }

  /**
   * Returns the effective entitlements for the given user. If the user cannot be resolved, the
   * default {@link Plan#FREE} tier is returned.
   */
  public Entitlements get(String userId) {
    return entitlementsFor(resolveUser(userId).orElse(null));
  }

  private Entitlements entitlementsFor(User user) {
    Plan plan = user != null && user.getPlan() != null ? user.getPlan() : Plan.FREE;
    TierConfig tier = billingProperties.tier(plan);
    ModelAccess access = billingProperties.getModelAccess();
    Double maxInput = priceCapInput(access, plan);
    Double maxOutput = priceCapOutput(access, plan);
    return new Entitlements(
        plan,
        tier.getMaxAttachments(),
        tier.getMaxAttachmentBytes(),
        tier.getMaxTotalAttachmentBytes(),
        tier.getContextMessageLimit(),
        tier.toolsView(),
        tier.getDailyCap(),
        tier.getParallelStreams(),
        maxInput,
        maxOutput);
  }

  /**
   * Returns the input-price cap (USD per 1M tokens) applied to the given plan, or {@code null}
   * when the plan has no cap (Max). Free uses {@code freeMax*}, Premium uses {@code premiumMax*}
   * (see BILL-8).
   */
  private static Double priceCapInput(ModelAccess access, Plan plan) {
    return switch (plan) {
      case FREE -> access.getFreeMaxInputUsdPerMillion();
      case PREMIUM -> access.getPremiumMaxInputUsdPerMillion();
      case MAX -> null;
    };
  }

  private static Double priceCapOutput(ModelAccess access, Plan plan) {
    return switch (plan) {
      case FREE -> access.getFreeMaxOutputUsdPerMillion();
      case PREMIUM -> access.getPremiumMaxOutputUsdPerMillion();
      case MAX -> null;
    };
  }

  /**
   * Verifies that the model request is valid for a BYOK call. BYOK is not plan-gated because the
   * user's own provider account pays for and constrains model access. See
   * {@link #assertCanUseModel(String, Provider, String, BillingMode)} for the managed-aware
   * variant.
   */
  public void assertCanUseModel(String userId, Provider provider, String modelId) {
    assertCanUseModel(userId, provider, modelId, BillingMode.BYOK);
  }

  /**
   * Verifies that the user's plan allows the given model for the given {@link BillingMode}.
   *
   * <p>Rules:
   * <ul>
   *   <li>BYOK calls only validate that a model id was provided. Plan price caps do not apply.
   *   <li>Managed-mode calls must target a provider listed in
   *       {@code app.billing.model-access.managed-providers} (default {@code [OPENROUTER]}) — our
   *       paid tiers route 100% through OpenRouter; direct OpenAI/Google/Claude are BYOK-only.
   *   <li>Free/Premium managed-mode users are restricted to models whose prompt and completion
   *       prices are at or below the configured price thresholds. Free managed-mode users are
   *       restricted to {@code openrouter/free}; storing a user key switches the call to BYOK and
   *       removes that managed-mode router restriction.
   *   <li>Max managed-mode users have no price cap.
   * </ul>
   *
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#MODEL_NOT_ALLOWED} if the model is not permitted.
   */
  public void assertCanUseModel(
      String userId, Provider provider, String modelId, BillingMode billingMode) {
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(billingMode, "billingMode must not be null");
    if (modelId == null || modelId.isBlank()) {
      Entitlements entitlements = get(userId);
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Model id must not be blank");
    }
    if (billingMode == BillingMode.BYOK) {
      return;
    }

    Entitlements entitlements = get(userId);
    if (billingMode == BillingMode.MANAGED
        && !billingProperties.getModelAccess().allowsManagedProvider(provider)) {
      Set<Provider> allowed = billingProperties.getModelAccess().getManagedProviders();
      String allowedList =
          allowed == null || allowed.isEmpty()
              ? "(none)"
              : allowed.stream().map(Enum::name).collect(Collectors.joining(", "));
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Provider %s is not available in managed billing mode; use BYOK or choose a model from an allowed provider (%s)"
              .formatted(provider, allowedList));
    }
    if (isFreeManagedOpenRouterRequest(entitlements, provider)
        && !ModelPricingLookup.OPENROUTER_FREE_MODEL_ID.equals(modelId)) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Free managed mode is restricted to %s unless a provider API key is stored"
              .formatted(ModelPricingLookup.OPENROUTER_FREE_MODEL_ID));
    }
    if (entitlements.allModelsAllowed()) {
      return;
    }
    double maxInput =
        entitlements.maxModelInputUsdPerMillion() != null
            ? entitlements.maxModelInputUsdPerMillion()
            : Double.POSITIVE_INFINITY;
    double maxOutput =
        entitlements.maxModelOutputUsdPerMillion() != null
            ? entitlements.maxModelOutputUsdPerMillion()
            : Double.POSITIVE_INFINITY;
    Optional<ModelPricingLookup.Pricing> pricing = modelPricingLookup.find(provider, modelId);
    if (pricing.isEmpty()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Model '%s' (provider %s) has no known pricing and is not available on plan %s"
              .formatted(modelId, provider, entitlements.plan()));
    }
    ModelPricingLookup.Pricing price = pricing.get();
    if (price.inputUsdPerMillion() > maxInput || price.outputUsdPerMillion() > maxOutput) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          ("Model '%s' (provider %s) exceeds plan %s price limits "
                  + "(input %.4f/1M, output %.4f/1M USD; allowed %.4f/%.4f)")
              .formatted(
                  modelId,
                  provider,
                  entitlements.plan(),
                  price.inputUsdPerMillion(),
                  price.outputUsdPerMillion(),
                  maxInput,
                  maxOutput));
    }
  }

  /**
   * Atomically reserves one managed-mode daily call slot. On success the caller must invoke
   * {@link ManagedQuotaReservation#commit()} after the provider call completes, or
   * {@link ManagedQuotaReservation#rollback()} / try-with-resources on failure.
   *
   * @return a no-op reservation when the tier has no daily cap or the user is not persisted
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#DAILY_CAP_EXCEEDED} when the cap is already exhausted
   */
  public ManagedQuotaReservation reserveManagedCall(String userId) {
    Optional<User> user = resolveUser(userId);
    Entitlements entitlements = entitlementsFor(user.orElse(null));
    long cap = entitlements.dailyCap();
    if (cap <= 0 || user.isEmpty()) {
      return ManagedQuotaReservation.noop();
    }
    long internalId = user.get().getId();
    LocalDate day = today();
    long count = dailyQuotaCounterRepository.incrementAndGetCount(internalId, day);
    if (count > cap) {
      dailyQuotaCounterRepository.decrementCounter(internalId, day);
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED,
          entitlements.plan(),
          "Daily call cap (%d) reached for plan %s".formatted(cap, entitlements.plan()));
    }
    return new ManagedQuotaReservation(dailyQuotaCounterRepository, internalId, day);
  }

  /**
   * Verifies that the user has not yet exhausted the daily managed-mode cap for their tier. A
   * non-positive cap disables the limit entirely.
   *
   * @deprecated Prefer {@link #reserveManagedCall(String)} for atomic reserve-at-start semantics.
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#DAILY_CAP_EXCEEDED} when the user is over quota.
   */
  @Deprecated
  public void assertWithinDailyCap(String userId) {
    Optional<User> user = resolveUser(userId);
    Entitlements entitlements = entitlementsFor(user.orElse(null));
    long cap = entitlements.dailyCap();
    if (cap <= 0) {
      return;
    }
    if (user.isEmpty()) {
      // No internal user record — treat as a fresh Free user at zero usage.
      return;
    }
    long current = dailyQuotaCounterRepository.getCount(user.get().getId(), today());
    if (current >= cap) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED,
          entitlements.plan(),
          "Daily call cap (%d) reached for plan %s".formatted(cap, entitlements.plan()));
    }
  }

  /**
   * Atomically increments the daily managed-call counter for the user. No-op when the user cannot
   * be resolved to a persistent record.
   */
  public void incrementCounter(String userId) {
    Optional<Long> internalId = resolveUser(userId).map(User::getId);
    if (internalId.isEmpty()) {
      log.debug("Skipping daily-quota increment: user {} not persisted", userId);
      return;
    }
    dailyQuotaCounterRepository.incrementCounter(internalId.get(), today());
  }

  /**
   * Resolves the requested {@link BillingMode} preference for the given user. The actual API key
   * and effective mode are chosen by {@link BillingModeResolver}; this method deliberately returns
   * {@link BillingMode#AUTO} unchanged so AUTO can prefer BYOK when a user key exists and fall back
   * to managed mode when the plan/model allows it.
   */
  public BillingMode resolveBillingMode(String userId) {
    return resolveUser(userId)
        .map(User::getBillingModePreference)
        .map(mode -> mode == null ? BillingMode.AUTO : mode)
        .orElse(BillingMode.AUTO);
  }

  /**
   * Annotates a provider model catalog with plan-awareness flags so the frontend can render
   * locked/upgrade indicators without re-deriving plan rules. The catalog itself is
   * <strong>not</strong> filtered: every input option is preserved and returned as a copy with
   * {@link ModelOption#getAvailability()} and {@link ModelOption#getRequiredPlan()} populated.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>If the user has a stored key for this provider, all returned models are marked
   *       {@link AvailabilityFlag#ALWAYS}; BYOK model access is not plan-gated.
   *   <li>Direct providers (anything not listed under {@code app.billing.model-access
   *       .managed-providers}) → {@link AvailabilityFlag#BYOK_ONLY}. The platform never pays for
   *       these endpoints; users must bring their own key regardless of plan.
   *   <li>Managed providers (default: OpenRouter):
   *       <ul>
   *         <li>{@link Plan#MAX} (no price cap) → {@link AvailabilityFlag#ALWAYS}.
   *         <li>{@link Plan#PREMIUM}: models at or below the configured
   *             {@code app.billing.model-access.premium-max-*-usd-per-million} thresholds are
   *             {@link AvailabilityFlag#ALWAYS}, else {@link AvailabilityFlag#UPGRADE_REQUIRED}
   *             with {@code requiredPlan = MAX}.
   *         <li>{@link Plan#FREE}: only {@code openrouter/free} is marked
   *             {@link AvailabilityFlag#ALWAYS}; other OpenRouter models are not selectable in
   *             managed mode unless the user stores a provider key or upgrades to a tier whose
   *             price cap allows the model.
   *       </ul>
   * </ul>
   */
  public List<ModelOption> annotateModelsForPlan(
      String userId, Provider provider, List<ModelOption> models) {
    return annotateModelsForPlan(userId, provider, models, false);
  }

  /**
   * Annotates a provider model catalog with an explicit BYOK hint. When {@code hasUserKey} is
   * {@code true}, model availability reflects the effective BYOK path: no plan upgrade is needed
   * for any model returned by that provider catalog.
   */
  public List<ModelOption> annotateModelsForPlan(
      String userId, Provider provider, List<ModelOption> models, boolean hasUserKey) {
    if (models == null || models.isEmpty()) {
      return models == null ? List.of() : models;
    }
    Objects.requireNonNull(provider, "provider must not be null");
    if (hasUserKey) {
      return models.stream()
          .filter(Objects::nonNull)
          .map(
              opt -> {
                ModelOption copy = opt.copy();
                copy.setAvailability(AvailabilityFlag.ALWAYS);
                copy.setRequiredPlan(null);
                return copy;
              })
          .toList();
    }
    Entitlements entitlements = get(userId);
    boolean managedProvider = billingProperties.getModelAccess().allowsManagedProvider(provider);
    return models.stream()
        .filter(Objects::nonNull)
        .map(
            opt -> {
              ModelOption copy = opt.copy();
              AvailabilityFlag flag;
              Plan required = null;
              if (!managedProvider) {
                // Direct providers are BYOK-only regardless of plan because managed mode only
                // routes through providers listed in app.billing.model-access.managed-providers.
                flag = AvailabilityFlag.BYOK_ONLY;
              } else if (isFreeManagedOpenRouterRequest(entitlements, provider)
                  && !ModelPricingLookup.OPENROUTER_FREE_MODEL_ID.equals(opt.getId())) {
                flag = AvailabilityFlag.UPGRADE_REQUIRED;
                Optional<ModelPricingLookup.Pricing> pricing =
                    modelPricingLookup.find(provider, opt.getId());
                required = requiredPlanFor(pricing);
              } else if (entitlements.allModelsAllowed()) {
                flag = AvailabilityFlag.ALWAYS;
              } else {
                Optional<ModelPricingLookup.Pricing> pricing =
                    modelPricingLookup.find(provider, opt.getId());
                if (isAllowedByPricing(entitlements, pricing)) {
                  flag = AvailabilityFlag.ALWAYS;
                } else {
                  flag = AvailabilityFlag.UPGRADE_REQUIRED;
                  required = requiredPlanFor(pricing);
                }
              }
              copy.setAvailability(flag);
              copy.setRequiredPlan(required);
              return copy;
            })
        .toList();
  }

  private boolean isFreeManagedOpenRouterRequest(Entitlements entitlements, Provider provider) {
    return entitlements.plan() == Plan.FREE && provider == Provider.OPENROUTER;
  }

  /**
   * Returns only models that the current user can actually select for the effective billing path.
   * The returned options are still annotated, but every returned model has
   * {@link AvailabilityFlag#ALWAYS}. This is the preferred shape for model-picker APIs: users
   * should not see plan-locked models unless a dedicated comparison/upsell surface asks for them.
   */
  public List<ModelOption> filterUsableModelsForPlan(
      String userId, Provider provider, List<ModelOption> models, boolean hasUserKey) {
    return annotateModelsForPlan(userId, provider, models, hasUserKey).stream()
        .filter(model -> model.getAvailability() == AvailabilityFlag.ALWAYS)
        .toList();
  }

  /**
   * @deprecated Use {@link #filterUsableModelsForPlan(String, Provider, List, boolean)} instead.
   *     Kept for backwards compatibility with the older OpenRouter-only filtering API.
   */
  @Deprecated
  public List<ModelCatalogProperties.ModelOption> filterOpenRouterModelsForPlan(
      String userId, List<ModelCatalogProperties.ModelOption> models) {
    return filterUsableModelsForPlan(userId, Provider.OPENROUTER, models, false);
  }

  /**
   * Mirrors the price-threshold gate in {@link #assertCanUseModel(String, Provider, String,
   * BillingMode)}: returns {@code true} when the user's plan allows the given model based on its
   * prompt/completion prices. Plans without a price cap ({@link Entitlements#allModelsAllowed()})
   * always allow every model; unknown pricing is treated as "not allowed" so the UI shows an
   * upgrade hint consistent with the enforcement path.
   */
  private boolean isAllowedByPricing(
      Entitlements entitlements, Optional<ModelPricingLookup.Pricing> pricing) {
    if (entitlements.allModelsAllowed()) {
      return true;
    }
    double maxInput =
        entitlements.maxModelInputUsdPerMillion() != null
            ? entitlements.maxModelInputUsdPerMillion()
            : Double.POSITIVE_INFINITY;
    double maxOutput =
        entitlements.maxModelOutputUsdPerMillion() != null
            ? entitlements.maxModelOutputUsdPerMillion()
            : Double.POSITIVE_INFINITY;
    if (pricing.isEmpty()) {
      return false;
    }
    ModelPricingLookup.Pricing p = pricing.get();
    return p.inputUsdPerMillion() <= maxInput && p.outputUsdPerMillion() <= maxOutput;
  }

  /**
   * Determines which plan the user must upgrade to in order to access the given model. Models
   * that fit within the Premium price cap require {@link Plan#PREMIUM}; models that exceed it
   * require {@link Plan#MAX}. Models with unknown pricing are conservatively treated as
   * requiring {@link Plan#MAX} because capped plans reject unknown pricing in the enforcement
   * path, so the suggested upgrade must be one that can actually unlock access.
   */
  private Plan requiredPlanFor(Optional<ModelPricingLookup.Pricing> pricing) {
    ModelAccess access = billingProperties.getModelAccess();
    if (pricing.isEmpty()) {
      return Plan.MAX;
    }
    ModelPricingLookup.Pricing p = pricing.get();
    if (p.inputUsdPerMillion() > access.getPremiumMaxInputUsdPerMillion()
        || p.outputUsdPerMillion() > access.getPremiumMaxOutputUsdPerMillion()) {
      return Plan.MAX;
    }
    return Plan.PREMIUM;
  }

  private Optional<User> resolveUser(String userId) {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    return userRepository.findByProviderId(userId);
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
