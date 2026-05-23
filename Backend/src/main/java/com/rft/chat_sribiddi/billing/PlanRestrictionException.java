package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;

/**
 * Thrown by {@link EntitlementService} when a user action is not permitted by their current
 * plan (e.g. model not in tier, daily-cap exceeded, tool disabled for tier).
 *
 * <p>{@link com.rft.chat_sribiddi.config.GlobalExceptionHandler} maps this to HTTP 402
 * ({@link Reason#MODEL_NOT_ALLOWED} / {@link Reason#DAILY_CAP_EXCEEDED}), 403
 * ({@link Reason#TOOL_NOT_ALLOWED}), or 503 ({@link Reason#MANAGED_PROVIDER_UNCONFIGURED}).
 */
public class PlanRestrictionException extends RuntimeException {

  public enum Reason {
    /** Model is not included in the user's current tier — upgrade required (HTTP 402). */
    MODEL_NOT_ALLOWED,
    /** User has exhausted their managed-mode daily call budget (HTTP 402). */
    DAILY_CAP_EXCEEDED,
    /** Tool is not available on the current tier (HTTP 403). */
    TOOL_NOT_ALLOWED,
    /** Server-managed provider access is disabled or missing its API key (HTTP 503). */
    MANAGED_PROVIDER_UNCONFIGURED
  }

  private final Reason reason;
  private final Plan plan;

  public PlanRestrictionException(Reason reason, Plan plan, String message) {
    super(message);
    this.reason = reason;
    this.plan = plan;
  }

  public Reason getReason() {
    return reason;
  }

  public Plan getPlan() {
    return plan;
  }
}
