package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.billing.BillingProperties.ModelAccess;
import com.rft.chat_sribiddi.billing.BillingProperties.TierConfig;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
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
    Plan plan = resolveUser(userId).map(User::getPlan).orElse(Plan.FREE);
    if (plan == null) {
      plan = Plan.FREE;
    }
    TierConfig tier = billingProperties.tier(plan);
    ModelAccess access = billingProperties.getModelAccess();
    Double maxInput = plan == Plan.FREE ? access.getFreeMaxInputUsdPerMillion() : null;
    Double maxOutput = plan == Plan.FREE ? access.getFreeMaxOutputUsdPerMillion() : null;
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
   * Verifies that the user's plan allows the given model. Premium/Max plans have no price cap.
   * Free users are restricted to models whose prompt and completion prices are at or below the
   * configured thresholds ({@code app.billing.model-access.free-max-{input,output}-usd-per-million}).
   *
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#MODEL_NOT_ALLOWED} if the model is not permitted or its
   *     price cannot be determined on the Free tier.
   */
  public void assertCanUseModel(String userId, Provider provider, String modelId) {
    Objects.requireNonNull(provider, "provider must not be null");
    Entitlements entitlements = get(userId);
    if (modelId == null || modelId.isBlank()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Model id must not be blank");
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
    Entitlements entitlements = get(userId);
    long cap = entitlements.dailyCap();
    if (cap <= 0) {
      return;
    }
    Optional<Long> internalId = resolveUser(userId).map(User::getId);
    if (internalId.isEmpty()) {
      // No internal user record — treat as a fresh Free user at zero usage.
      return;
    }
    long current = dailyQuotaCounterRepository.getCount(internalId.get(), today());
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
