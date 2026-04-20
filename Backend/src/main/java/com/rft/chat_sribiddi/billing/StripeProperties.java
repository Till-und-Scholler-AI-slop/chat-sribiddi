package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stripe configuration for BILL-11 (checkout, customer portal) and BILL-10 (webhook). All
 * tuneable values are read from {@code app.billing.stripe.*} in {@code application.yaml} so that
 * secrets and price IDs are never hardcoded. A blank {@link #getSecretKey()} disables the Stripe
 * integration — {@link StripeService} returns HTTP 503 for affected endpoints.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.billing.stripe")
public class StripeProperties {

  /** Server-side Stripe API key ({@code sk_test_…} / {@code sk_live_…}). Blank disables Stripe. */
  private String secretKey = "";

  /** Webhook signing secret used by the BILL-10 webhook handler. */
  private String webhookSecret = "";

  /** Stripe Price ID for the Premium plan billed monthly. */
  private String priceIdPremiumMonth = "";

  /** Stripe Price ID for the Premium plan billed yearly. */
  private String priceIdPremiumYear = "";

  /** Stripe Price ID for the Max plan billed monthly. */
  private String priceIdMaxMonth = "";

  /** Stripe Price ID for the Max plan billed yearly. */
  private String priceIdMaxYear = "";

  /**
   * URL Stripe redirects to after a successful Checkout Session. May contain the
   * {@code {CHECKOUT_SESSION_ID}} placeholder which Stripe substitutes server-side.
   */
  private String successUrl = "";

  /** URL Stripe redirects to when a Checkout Session is cancelled. */
  private String cancelUrl = "";

  /** URL Stripe redirects to after a Customer Portal session. */
  private String portalReturnUrl = "";

  /** True when a Stripe secret key is configured. */
  public boolean isConfigured() {
    return secretKey != null && !secretKey.isBlank();
  }

  /**
   * Billing interval for a Stripe subscription price. Mirrored from the issue DTO spec so that
   * request validation and internal mapping share a single type.
   */
  public enum Interval {
    MONTH,
    YEAR
  }

  /**
   * Resolves the Stripe Price ID for a (plan, interval) pair. Returns an empty string when the
   * combination is not configured (e.g. {@link Plan#FREE}) or when the corresponding env var is
   * blank.
   */
  public String resolvePriceId(Plan plan, Interval interval) {
    Map<Interval, String> prices = priceMap().get(plan);
    if (prices == null) {
      return "";
    }
    String priceId = prices.get(interval);
    return priceId == null ? "" : priceId;
  }

  private Map<Plan, Map<Interval, String>> priceMap() {
    Map<Interval, String> premium = new EnumMap<>(Interval.class);
    premium.put(Interval.MONTH, priceIdPremiumMonth);
    premium.put(Interval.YEAR, priceIdPremiumYear);
    Map<Interval, String> max = new EnumMap<>(Interval.class);
    max.put(Interval.MONTH, priceIdMaxMonth);
    max.put(Interval.YEAR, priceIdMaxYear);
    Map<Plan, Map<Interval, String>> byPlan = new EnumMap<>(Plan.class);
    byPlan.put(Plan.PREMIUM, premium);
    byPlan.put(Plan.MAX, max);
    return byPlan;
  }
}
