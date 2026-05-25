package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tier billing/entitlements configuration. Values are sourced from {@code application.yaml}
 * under {@code app.billing.*} so that plan-gating limits are never hardcoded in service classes
 * (repo convention).
 *
 * <p>Model access is <em>not</em> gated via hardcoded ID whitelists. Instead, the Free tier is
 * restricted by price thresholds (see {@link ModelAccess}): only models whose prompt and
 * completion prices are at or below the configured threshold are allowed on Free. Premium has a
 * configurable price cap, while Max has no price cap.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.billing")
public class BillingProperties {

  /**
   * Per-plan tier configuration. Keys match {@link Plan} (FREE, PREMIUM, MAX), matched
   * case-insensitively by Spring Boot.
   */
  private Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);

  /** Maximum managed-mode chat calls per anonymous browser/IP per UTC day. */
  private long guestDailyCap = 20L;

  /** Price-based model-access rules applied on top of per-tier limits. */
  private ModelAccess modelAccess = new ModelAccess();

  /** Returns the configuration for a plan, falling back to an empty config if missing. */
  public TierConfig tier(Plan plan) {
    TierConfig config = tiers.get(plan);
    return config != null ? config : new TierConfig();
  }

  @PostConstruct
  void validateDailyCaps() {
    if (guestDailyCap <= 0) {
      throw new IllegalStateException(
          "app.billing.guest-daily-cap must be greater than 0 (was " + guestDailyCap + ")");
    }
    for (Plan plan : Plan.values()) {
      long cap = tier(plan).getDailyCap();
      if (cap <= 0) {
        throw new IllegalStateException(
            "app.billing.tiers.%s.daily-cap must be greater than 0 (was %d)"
                .formatted(plan.name().toLowerCase(), cap));
      }
    }
  }

  @Getter
  @Setter
  public static class TierConfig {
    /** Maximum number of media attachments per message. */
    private int maxAttachments = 0;

    /** Maximum size of a single media attachment in bytes. */
    private long maxAttachmentBytes = 0L;

    /** Maximum total size of all media attachments on a single request in bytes. */
    private long maxTotalAttachmentBytes = 0L;

    /** Maximum number of prior messages included as chat context. */
    private int contextMessageLimit = 0;

    /** Set of allowed tool identifiers (e.g. {@code brave-search}). */
    private Set<String> tools = new LinkedHashSet<>();

    /** Maximum number of managed-mode calls allowed per user per day. */
    private long dailyCap = 0L;

    /** Maximum number of parallel streams allowed for a user on this tier. */
    private int parallelStreams = 1;

    public boolean allowsTool(String toolId) {
      if (toolId == null || toolId.isBlank() || tools == null) {
        return false;
      }
      if (tools.contains("*")) {
        return true;
      }
      return tools.contains(toolId);
    }

    public Set<String> toolsView() {
      return tools == null ? Set.of() : new HashSet<>(tools);
    }
  }

  /**
   * Price-threshold configuration for model access. Expressed in USD per 1,000,000 tokens to
   * match industry-standard pricing tables. Free defaults (0.0 / 0.0) restrict the managed path
   * to OpenRouter's {@code openrouter/free} router model; Premium defaults (5.0 / 5.0) cap
   * Premium users to mid-range models — anything above the threshold
   * (e.g. {@code claude-opus-4-7} at ~$15/1M) requires upgrading to {@link Plan#MAX}.
   */
  @Getter
  @Setter
  public static class ModelAccess {
    /** Maximum prompt/input price in USD per 1M tokens allowed on the Free tier. */
    private double freeMaxInputUsdPerMillion = 0.0;

    /** Maximum completion/output price in USD per 1M tokens allowed on the Free tier. */
    private double freeMaxOutputUsdPerMillion = 0.0;

    /**
     * Maximum prompt/input price in USD per 1M tokens allowed on the Premium tier. Models whose
     * prompt price exceeds this threshold (e.g. premium frontier models like claude-opus-4-7)
     * require upgrading to {@link Plan#MAX}. See BILL-8.
     */
    private double premiumMaxInputUsdPerMillion = 5.0;

    /**
     * Maximum completion/output price in USD per 1M tokens allowed on the Premium tier. Models
     * whose completion price exceeds this threshold require upgrading to {@link Plan#MAX}.
     */
    private double premiumMaxOutputUsdPerMillion = 5.0;

    /**
     * Providers allowed for managed-mode (platform-paid) calls. Defaults to OpenRouter only —
     * direct provider APIs (OpenAI/Google/Claude) are BYOK-only because our paid tiers route
     * 100% through OpenRouter. Configure explicit provider enum values to allow additional
     * managed-mode providers.
     */
    private Set<com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider> managedProviders =
        new LinkedHashSet<>(
            Set.of(com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider.OPENROUTER));

    public boolean allowsManagedProvider(
        com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider provider) {
      if (provider == null || managedProviders == null) {
        return false;
      }
      return managedProviders.contains(provider);
    }
  }
}
