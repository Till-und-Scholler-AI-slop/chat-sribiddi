package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Billing endpoints (BILL-11). Exposes Stripe Checkout / Customer Portal creation plus
 * lightweight read endpoints for the current plan state and recent usage.
 *
 * <p>Endpoints are registered without the {@code /api} prefix per repo convention (see
 * {@link com.rft.chat_sribiddi.auth.AccountController}). Auth follows the same pattern as the
 * other account endpoints: {@link OidcUser} in production, falling back to the
 * {@code X-Chat-User-Id} header in the dev profile via {@link RequestUserResolver}.
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

  private static final Logger log = LoggerFactory.getLogger(BillingController.class);

  /** Window used by {@code GET /billing/usage}: the trailing 30 days of {@code usage_events}. */
  static final Duration USAGE_WINDOW = Duration.ofDays(30);

  private final StripeService stripeService;
  private final StripeProperties stripeProperties;
  private final UserRepository userRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final UsageEventRepository usageEventRepository;
  private final RequestUserResolver requestUserResolver;
  private final Clock clock;

  @Autowired
  public BillingController(
      StripeService stripeService,
      StripeProperties stripeProperties,
      UserRepository userRepository,
      SubscriptionRepository subscriptionRepository,
      UsageEventRepository usageEventRepository,
      RequestUserResolver requestUserResolver) {
    this(
        stripeService,
        stripeProperties,
        userRepository,
        subscriptionRepository,
        usageEventRepository,
        requestUserResolver,
        Clock.system(ZoneId.of("UTC")));
  }

  BillingController(
      StripeService stripeService,
      StripeProperties stripeProperties,
      UserRepository userRepository,
      SubscriptionRepository subscriptionRepository,
      UsageEventRepository usageEventRepository,
      RequestUserResolver requestUserResolver,
      Clock clock) {
    this.stripeService = stripeService;
    this.stripeProperties = stripeProperties;
    this.userRepository = userRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.usageEventRepository = usageEventRepository;
    this.requestUserResolver = requestUserResolver;
    this.clock = clock;
  }

  @PostMapping("/checkout-session")
  public ResponseEntity<UrlResponse> createCheckoutSession(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestBody CheckoutSessionRequest body) {
    validate(body);
    User user = requireUser(principal, request);
    String url;
    try {
      url = stripeService.createCheckoutSession(user, stripeProperties.resolvePriceId(body.plan(), body.interval()));
    } catch (StripeException ex) {
      throw toStripeStatusException(ex);
    }
    return ResponseEntity.ok(new UrlResponse(url));
  }

  @PostMapping("/portal-session")
  public ResponseEntity<UrlResponse> createPortalSession(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    User user = requireUser(principal, request);
    String url;
    try {
      url = stripeService.createPortalSession(user);
    } catch (StripeException ex) {
      throw toStripeStatusException(ex);
    }
    return ResponseEntity.ok(new UrlResponse(url));
  }

  @GetMapping("/me")
  public ResponseEntity<BillingMeResponse> me(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    User user = requireUser(principal, request);
    SubscriptionEntity subscription =
        subscriptionRepository.findFirstByUserIdOrderByUpdatedAtDesc(user.getId()).orElse(null);
    Plan plan = user.getPlan() == null ? Plan.FREE : user.getPlan();
    String status =
        subscription != null && subscription.getStatus() != null
            ? subscription.getStatus()
            : user.getPlanStatus();
    Instant periodEnd =
        subscription != null && subscription.getCurrentPeriodEnd() != null
            ? subscription.getCurrentPeriodEnd()
            : user.getPlanValidUntil();
    boolean cancelAtPeriodEnd = subscription != null && subscription.isCancelAtPeriodEnd();
    BillingMode billingModePreference =
        user.getBillingModePreference() == null ? BillingMode.AUTO : user.getBillingModePreference();
    return ResponseEntity.ok(
        new BillingMeResponse(plan, status, periodEnd, cancelAtPeriodEnd, billingModePreference));
  }

  @GetMapping("/usage")
  public ResponseEntity<BillingUsageResponse> usage(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    User user = requireUser(principal, request);
    Instant now = clock.instant();
    Instant since = now.minus(USAGE_WINDOW);
    List<UsageEventRepository.UsageAggregate> aggregates =
        usageEventRepository.aggregateSince(user.getId(), since);
    List<UsageItem> items =
        aggregates.stream()
            .map(
                a ->
                    new UsageItem(
                        a.getProvider(),
                        a.getModelId(),
                        a.getCalls(),
                        a.getPromptTokens(),
                        a.getCompletionTokens(),
                        a.getTotalTokens(),
                        a.getCostMicroCents()))
            .toList();
    long totalCalls = items.stream().mapToLong(UsageItem::calls).sum();
    long totalTokens = items.stream().mapToLong(UsageItem::totalTokens).sum();
    long totalCostMicroCents = items.stream().mapToLong(UsageItem::costMicroCents).sum();
    return ResponseEntity.ok(
        new BillingUsageResponse(
            since, now, totalCalls, totalTokens, totalCostMicroCents, items));
  }

  private User requireUser(OidcUser principal, HttpServletRequest request) {
    String providerId =
        requestUserResolver
            .resolveUserId(principal, request)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    return userRepository
        .findByProviderId(providerId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
  }

  private static void validate(CheckoutSessionRequest body) {
    if (body == null || body.plan() == null || body.interval() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "plan and interval are required");
    }
    if (body.plan() == Plan.FREE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Free tier does not require a checkout session");
    }
  }


  private static ResponseStatusException toStripeStatusException(StripeException ex) {
    Integer status = ex.getStatusCode();
    HttpStatus mapped =
        status != null && status >= 400 && status < 500
            ? HttpStatus.BAD_REQUEST
            : HttpStatus.BAD_GATEWAY;
    // Generate a short correlation id so the operator can cross-reference the detailed
    // server-side log entry from the client error without leaking internals (Stripe error
    // messages can include API keys, internal IDs, rate-limit hints, etc.).
    String correlationId = UUID.randomUUID().toString().substring(0, 8);
    log.warn(
        "Stripe API call failed (correlationId={}, status={}, requestId={}, code={}): {}",
        correlationId,
        status,
        ex.getRequestId(),
        ex.getCode(),
        ex.getMessage(),
        ex);
    String clientMessage =
        mapped == HttpStatus.BAD_REQUEST
            ? "The billing request was rejected by Stripe (ref: " + correlationId + ")"
            : "Failed to reach Stripe (ref: " + correlationId + ")";
    return new ResponseStatusException(mapped, clientMessage, ex);
  }

  public record CheckoutSessionRequest(
      @NotNull Plan plan, @NotNull StripeProperties.Interval interval) {}

  public record UrlResponse(String url) {}

  public record BillingMeResponse(
      Plan plan,
      String status,
      Instant periodEnd,
      boolean cancelAtPeriodEnd,
      BillingMode billingModePreference) {}

  public record BillingUsageResponse(
      Instant since,
      Instant until,
      long totalCalls,
      long totalTokens,
      long totalCostMicroCents,
      List<UsageItem> items) {}

  public record UsageItem(
      Provider provider,
      String modelId,
      long calls,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long costMicroCents) {}
}
