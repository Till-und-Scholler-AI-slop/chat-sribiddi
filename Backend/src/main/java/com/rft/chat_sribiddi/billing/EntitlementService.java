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
 * price thresholds configured under {@code app.billing.model-access.*} (default: only truly-free
 * models, matching OpenRouter's {@code :free} convention).
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
   * Verifies that the user's plan allows the given model, assuming a BYOK call (no managed-mode
   * provider restriction applied). See {@link #assertCanUseModel(String, Provider, String,
   * BillingMode)} for the managed-aware variant.
   */
  public void assertCanUseModel(String userId, Provider provider, String modelId) {
    assertCanUseModel(userId, provider, modelId, BillingMode.BYOK);
  }

  /**
   * Verifies that the user's plan allows the given model for the given {@link BillingMode}.
   *
   * <p>Rules:
   * <ul>
   *   <li>Managed-mode calls must target a provider listed in
   *       {@code app.billing.model-access.managed-providers} (default {@code [OPENROUTER]}) — our
   *       paid tiers route 100% through OpenRouter; direct OpenAI/Google/Claude are BYOK-only.
   *   <li>Free users are restricted to models whose prompt and completion prices are at or below
   *       the configured price thresholds.
   *   <li>Premium/Max have no price cap.
   * </ul>
   *
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#MODEL_NOT_ALLOWED} if the model is not permitted.
   */
  public void assertCanUseModel(
      String userId, Provider provider, String modelId, BillingMode billingMode) {
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(billingMode, "billingMode must not be null");
    Entitlements entitlements = get(userId);
    if (modelId == null || modelId.isBlank()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Model id must not be blank");
    }
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
   * Verifies that the user has not yet exhausted the daily managed-mode cap for their tier. A
   * non-positive cap disables the limit entirely.
   *
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#DAILY_CAP_EXCEEDED} when the user is over quota.
   */
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
   * Resolves the effective {@link BillingMode} for the given user. Uses the user's
   * {@code billingModePreference}; {@link BillingMode#AUTO} is treated as
   * {@link BillingMode#BYOK} so that users who bring their own API key are never counted
   * against managed-mode quotas. Unknown users default to {@link BillingMode#BYOK}.
   */
  public BillingMode resolveBillingMode(String userId) {
    return resolveUser(userId)
        .map(User::getBillingModePreference)
        .map(mode -> mode == null || mode == BillingMode.AUTO ? BillingMode.BYOK : mode)
        .orElse(BillingMode.BYOK);
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
   *         <li>{@link Plan#FREE}: models at or below the {@code free-max-*} thresholds are
   *             {@link AvailabilityFlag#ALWAYS}; more expensive models are
   *             {@link AvailabilityFlag#UPGRADE_REQUIRED} with {@code requiredPlan = PREMIUM}
   *             unless they also exceed the Premium thresholds, in which case
   *             {@code requiredPlan = MAX}. Models with unknown pricing default to
   *             {@code UPGRADE_REQUIRED} with {@code requiredPlan = PREMIUM} (matching the
   *             enforcement path).
   *       </ul>
   * </ul>
   */
  public List<ModelOption> annotateModelsForPlan(
      String userId, Provider provider, List<ModelOption> models) {
    if (models == null || models.isEmpty()) {
      return models == null ? List.of() : models;
    }
    Objects.requireNonNull(provider, "provider must not be null");
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
              } else if (isAllowedByPricing(entitlements, provider, opt.getId())) {
                flag = AvailabilityFlag.ALWAYS;
              } else {
                flag = AvailabilityFlag.UPGRADE_REQUIRED;
                required = requiredPlanFor(provider, opt.getId());
              }
              copy.setAvailability(flag);
              copy.setRequiredPlan(required);
              return copy;
            })
        .toList();
  }

  /**
   * @deprecated Use {@link #annotateModelsForPlan(String, Provider, List)} instead. Kept for
   *     backwards compatibility — no longer filters models and returns the annotated copies
   *     produced by that method for the OpenRouter provider.
   */
  @Deprecated
  public List<ModelCatalogProperties.ModelOption> filterOpenRouterModelsForPlan(
      String userId, List<ModelCatalogProperties.ModelOption> models) {
    return annotateModelsForPlan(userId, Provider.OPENROUTER, models);
  }

  /**
   * Mirrors the price-threshold gate in {@link #assertCanUseModel(String, Provider, String,
   * BillingMode)}: returns {@code true} when the user's plan allows the given model based on its
   * prompt/completion prices. Plans without a price cap ({@link Entitlements#allModelsAllowed()})
   * always allow every model; unknown pricing is treated as "not allowed" so the UI shows an
   * upgrade hint consistent with the enforcement path.
   */
  private boolean isAllowedByPricing(Entitlements entitlements, Provider provider, String modelId) {
    if (entitlements.allModelsAllowed()) {
      return true;
    }
    if (modelId == null || modelId.isBlank()) {
      return false;
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
      return false;
    }
    ModelPricingLookup.Pricing p = pricing.get();
    return p.inputUsdPerMillion() <= maxInput && p.outputUsdPerMillion() <= maxOutput;
  }

  /**
   * Determines which plan the user must upgrade to in order to access the given model. Models
   * that fit within the Premium price cap require {@link Plan#PREMIUM}; models that exceed it
   * require {@link Plan#MAX}. Models with unknown pricing are conservatively treated as
   * requiring {@link Plan#PREMIUM} to match the FREE-tier enforcement path (see
   * {@link #isAllowedByPricing}), so users aren't pushed to MAX for a model whose price simply
   * isn't in our lookup yet.
   */
  private Plan requiredPlanFor(Provider provider, String modelId) {
    ModelAccess access = billingProperties.getModelAccess();
    Optional<ModelPricingLookup.Pricing> pricing = modelPricingLookup.find(provider, modelId);
    if (pricing.isEmpty()) {
      return Plan.PREMIUM;
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
