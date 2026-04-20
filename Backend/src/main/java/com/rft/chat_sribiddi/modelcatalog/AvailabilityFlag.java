package com.rft.chat_sribiddi.modelcatalog;

/**
 * Plan-awareness flag that accompanies every {@link ModelCatalogProperties.ModelOption} returned
 * by {@code GET /models/*}. The frontend uses this to render locked badges / upgrade prompts
 * without having to re-derive plan rules.
 *
 * <ul>
 *   <li>{@link #ALWAYS} — the model is usable for the current user on their current plan.
 *   <li>{@link #BYOK_ONLY} — the model is only available when the user brings their own API key
 *       (e.g. direct OpenAI/Google/Claude endpoints, which managed-mode never routes to).
 *   <li>{@link #UPGRADE_REQUIRED} — the user's plan forbids the model; upgrading to the plan
 *       advertised in {@code requiredPlan} unlocks it.
 * </ul>
 */
public enum AvailabilityFlag {
  ALWAYS,
  BYOK_ONLY,
  UPGRADE_REQUIRED
}
