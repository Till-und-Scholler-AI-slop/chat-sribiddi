package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
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
 * effective {@link Entitlements}, and enforces per-request plan restrictions (allowed models,
 * tools, daily-cap).
 *
 * <p>Configuration is sourced from {@link BillingProperties} ({@code app.billing.tiers.<plan>.*})
 * — no limits are hardcoded here.
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
  private final Clock clock;

  public EntitlementService(
      BillingProperties billingProperties,
      UserRepository userRepository,
      DailyQuotaCounterRepository dailyQuotaCounterRepository) {
    this(
        billingProperties,
        userRepository,
        dailyQuotaCounterRepository,
        Clock.system(ZoneId.of("UTC")));
  }

  EntitlementService(
      BillingProperties billingProperties,
      UserRepository userRepository,
      DailyQuotaCounterRepository dailyQuotaCounterRepository,
      Clock clock) {
    this.billingProperties = billingProperties;
    this.userRepository = userRepository;
    this.dailyQuotaCounterRepository = dailyQuotaCounterRepository;
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
    return new Entitlements(
        plan,
        tier.modelsView(),
        tier.allowsAllModels(),
        tier.getMaxAttachments(),
        tier.getMaxAttachmentBytes(),
        tier.getMaxTotalAttachmentBytes(),
        tier.getContextMessageLimit(),
        tier.toolsView(),
        tier.getDailyCap(),
        tier.getParallelStreams());
  }

  /**
   * Verifies that the user's plan allows the given model.
   *
   * @throws PlanRestrictionException with reason {@link
   *     PlanRestrictionException.Reason#MODEL_NOT_ALLOWED} if the model is not permitted.
   */
  public void assertCanUseModel(String userId, Provider provider, String modelId) {
    Objects.requireNonNull(provider, "provider must not be null");
    if (modelId == null || modelId.isBlank()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          get(userId).plan(),
          "Model id must not be blank");
    }
    Entitlements entitlements = get(userId);
    if (!entitlements.allowsModel(modelId)) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          entitlements.plan(),
          "Model '%s' (provider %s) is not available on plan %s"
              .formatted(modelId, provider, entitlements.plan()));
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
