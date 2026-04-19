package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import java.util.Set;

/**
 * Immutable snapshot of a user's effective entitlements. Returned by
 * {@link EntitlementService#get(String)} so callers never need to inspect {@link BillingProperties}
 * or the {@link com.rft.chat_sribiddi.auth.User} directly.
 */
public record Entitlements(
    Plan plan,
    Set<String> allowedModels,
    boolean allModelsAllowed,
    int maxAttachments,
    long maxAttachmentBytes,
    long maxTotalAttachmentBytes,
    int contextMessageLimit,
    Set<String> allowedTools,
    long dailyCap,
    int parallelStreams) {

  public boolean allowsModel(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return false;
    }
    return allModelsAllowed || allowedModels.contains(modelId);
  }

  public boolean allowsTool(String toolId) {
    if (toolId == null || toolId.isBlank() || allowedTools == null) {
      return false;
    }
    return allowedTools.contains("*") || allowedTools.contains(toolId);
  }
}
