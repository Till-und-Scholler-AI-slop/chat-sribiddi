package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import java.util.Set;

/**
 * Immutable snapshot of a user's effective entitlements. Returned by
 * {@link EntitlementService#get(String)} so callers never need to inspect {@link BillingProperties}
 * or the {@link com.rft.chat_sribiddi.auth.User} directly.
 *
 * <p>{@code maxModelInputUsdPerMillion} / {@code maxModelOutputUsdPerMillion} express the
 * price-based gating applied to managed-mode model selection: when non-null, a model is allowed
 * only if its prompt/completion price is at or below these thresholds (USD per 1M tokens). When
 * null, there is no price cap (Max).
 */
public record Entitlements(
    Plan plan,
    int maxAttachments,
    long maxAttachmentBytes,
    long maxTotalAttachmentBytes,
    int contextMessageLimit,
    Set<String> allowedTools,
    long dailyCap,
    int parallelStreams,
    Double maxModelInputUsdPerMillion,
    Double maxModelOutputUsdPerMillion) {

  public boolean allowsTool(String toolId) {
    if (toolId == null || toolId.isBlank() || allowedTools == null) {
      return false;
    }
    return allowedTools.contains("*") || allowedTools.contains(toolId);
  }

  /** True when there is no price cap on managed-mode model selection for this plan. */
  public boolean allModelsAllowed() {
    return maxModelInputUsdPerMillion == null && maxModelOutputUsdPerMillion == null;
  }
}
