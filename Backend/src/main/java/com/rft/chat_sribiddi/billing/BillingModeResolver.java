package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective API key and billing mode for a given chat request.
 *
 * <p>Used by the provider-client layer to decide whether to call a provider with the user's own
 * stored key (BYOK) or with a server-managed key (MANAGED). The resolver enforces plan/entitlement
 * rules via {@link EntitlementService} for managed-mode calls and raises
 * {@link PlanRestrictionException} when the requested combination is not permitted.
 *
 * <p>Resolution rules (BILL-6):
 * <ol>
 *   <li>A stored user key always wins → {@link BillingMode#BYOK}. BYOK calls validate the request
 *       shape but do not apply plan-level model price caps; the user's provider account decides
 *       which models the key can actually use.</li>
 *   <li>{@code requestedMode == AUTO}: prefer BYOK when a user key is present; otherwise fall back
 *       to {@link BillingMode#MANAGED} if the plan/model allows it.</li>
 *   <li>{@code requestedMode == MANAGED} and no user key exists: use the server-side env key for
 *       the provider (e.g. {@code OPENROUTER_API_KEY}) after the managed-mode entitlement check
 *       passes.</li>
 *   <li>Otherwise → {@link PlanRestrictionException}.</li>
 * </ol>
 */
@Slf4j
@Component
public class BillingModeResolver {

  private final ApiKeyService apiKeyService;
  private final EntitlementService entitlementService;
  private final ManagedApiKeyProperties managedKeys;

  public BillingModeResolver(
      ApiKeyService apiKeyService,
      EntitlementService entitlementService,
      ManagedApiKeyProperties managedKeys) {
    this.apiKeyService = apiKeyService;
    this.entitlementService = entitlementService;
    this.managedKeys = managedKeys;
  }

  /**
   * Resolves the effective {@link BillingMode} and API key for the given request.
   *
   * @param userId OAuth provider id of the caller; may be {@code null} for anonymous callers
   *     (anonymous callers can only succeed with an explicitly allowed MANAGED path).
   * @param provider target chat provider
   * @param modelId target model id (required for plan/model gating)
   * @param requestedMode the billing mode requested by the caller; {@code null} is treated as
   *     {@link BillingMode#AUTO}.
   * @return a {@link ResolvedKey} containing the resolved API key and the effective billing mode
   * @throws PlanRestrictionException if the requested combination is not permitted
   */
  public ResolvedKey resolve(
      String userId, Provider provider, String modelId, BillingMode requestedMode) {
    Objects.requireNonNull(provider, "provider must not be null");
    rejectNonChatProvider(provider);
    BillingMode mode = requestedMode == null ? BillingMode.AUTO : requestedMode;
    Optional<String> userKey = lookupUserKey(userId, provider);

    if (userKey.isPresent()) {
      return resolveByok(userId, provider, modelId, userKey);
    }

    return switch (mode) {
      case BYOK -> resolveByok(userId, provider, modelId, userKey);
      case AUTO, MANAGED -> resolveManaged(userId, provider, modelId);
    };
  }

  private ResolvedKey resolveByok(
      String userId, Provider provider, String modelId, Optional<String> userKey) {
    if (userKey.isEmpty()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          resolvePlan(userId),
          "BYOK billing mode requested but no stored API key found for provider %s"
              .formatted(provider));
    }
    // BYOK only validates request shape here. Plan/model price gates are managed-mode limits and
    // must not block users who pay the provider directly with their own key.
    entitlementService.assertCanUseModel(userId, provider, modelId, BillingMode.BYOK);
    return new ResolvedKey(userKey.get(), BillingMode.BYOK);
  }

  private ResolvedKey resolveManaged(String userId, Provider provider, String modelId) {
    // Throws PlanRestrictionException if the plan or model is not allowed for managed mode.
    entitlementService.assertCanUseModel(userId, provider, modelId, BillingMode.MANAGED);

    String serverKey = serverKeyFor(provider);
    if (serverKey == null || serverKey.isBlank()) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED,
          resolvePlan(userId),
          "Managed billing mode is not configured for provider %s (server key missing)"
              .formatted(provider));
    }
    return new ResolvedKey(serverKey, BillingMode.MANAGED);
  }

  private Optional<String> lookupUserKey(String userId, Provider provider) {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    Optional<String> stored = apiKeyService.getKey(userId, provider);
    return stored == null ? Optional.empty() : stored.filter(k -> !k.isBlank());
  }

  private void rejectNonChatProvider(Provider provider) {
    if (provider == Provider.BRAVE_SEARCH) {
      throw new IllegalArgumentException("BRAVE_SEARCH is not a chat model provider");
    }
  }

  private Plan resolvePlan(String userId) {
    try {
      return entitlementService.get(userId).plan();
    } catch (RuntimeException ex) {
      log.debug("Could not resolve plan for user {}: {}", userId, ex.getMessage());
      return null;
    }
  }

  private String serverKeyFor(Provider provider) {
    return switch (provider) {
      case OPENAI -> managedKeys.getOpenai();
      case GOOGLE -> managedKeys.getGoogle();
      case CLAUDE -> managedKeys.getClaude();
      case OPENROUTER -> managedKeys.getOpenrouter();
      case BRAVE_SEARCH ->
          throw new IllegalArgumentException("BRAVE_SEARCH is not a chat model provider");
    };
  }

  /** Resolved API key together with the effective {@link BillingMode} under which it applies. */
  public record ResolvedKey(String apiKey, BillingMode effectiveMode) {
    public ResolvedKey {
      Objects.requireNonNull(apiKey, "apiKey must not be null");
      Objects.requireNonNull(effectiveMode, "effectiveMode must not be null");
    }
  }
}
