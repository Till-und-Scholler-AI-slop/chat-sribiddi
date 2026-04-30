package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thin wrapper around the Stripe Java SDK. Encapsulates Checkout Session and Customer Portal
 * creation so that {@link BillingController} stays free of SDK imports and can be covered by plain
 * unit tests via Mockito.
 *
 * <p>The Stripe secret key is sourced from {@link StripeProperties}. If no key is configured
 * ({@code app.billing.stripe.secret-key} is blank) callers receive an HTTP 503 so that the whole
 * application can start up in environments (local dev, CI) that do not have a Stripe account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeService {

  private final StripeProperties stripeProperties;
  private final UserRepository userRepository;

  /**
   * Creates (or reuses) a Stripe Customer for the given internal user and returns its id. The
   * customer id is persisted on {@link User#getStripeCustomerId()} so subsequent Checkout /
   * Portal calls reuse the same record.
   *
   * <p>Concurrency: acquires a pessimistic row-lock on the user record before checking/creating
   * the Stripe Customer, so two concurrent requests for the same user serialize on the lock and
   * only one Stripe Customer is ever created. The lock is held for the duration of the Stripe
   * API call, which is acceptable here because Customer creation is a single fast call and this
   * method is only invoked from the {@code /billing/*} endpoints (one per user interaction).
   */
  @Transactional
  public String ensureCustomer(User user) throws StripeException {
    Objects.requireNonNull(user, "user must not be null");
    requireConfigured();
    // Fast path: the caller already loaded a user that has a customer id. Still re-read under a
    // pessimistic lock below in the slow path to avoid the race entirely.
    String existing = user.getStripeCustomerId();
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    User locked =
        userRepository
            .findWithLockById(user.getId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
    String lockedCustomerId = locked.getStripeCustomerId();
    if (lockedCustomerId != null && !lockedCustomerId.isBlank()) {
      // Another concurrent caller won the race and already created a customer — reuse it and
      // propagate onto the detached entity the caller is holding.
      user.setStripeCustomerId(lockedCustomerId);
      return lockedCustomerId;
    }
    CustomerCreateParams params =
        CustomerCreateParams.builder()
            .setEmail(locked.getEmail())
            .putMetadata("user_id", String.valueOf(locked.getId()))
            .putMetadata(
                "provider_id", locked.getProviderId() == null ? "" : locked.getProviderId())
            .build();
    Customer customer = Customer.create(params, requestOptions());
    locked.setStripeCustomerId(customer.getId());
    userRepository.save(locked);
    user.setStripeCustomerId(customer.getId());
    log.debug("Created Stripe customer {} for user id={}", customer.getId(), locked.getId());
    return customer.getId();
  }

  /**
   * Creates a Stripe Checkout Session for the given price id and returns the hosted checkout
   * URL. Subscription mode is used because all of our paid plans are recurring (see
   * MONETIZATION_PLAN.md). The customer id is linked so Stripe associates the resulting
   * subscription with our {@link User}.
   */
  public String createCheckoutSession(User user, String priceId) throws StripeException {
    Objects.requireNonNull(user, "user must not be null");
    if (priceId == null || priceId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Stripe price ID for the requested plan/interval is not configured");
    }
    requireConfigured();
    requireReturnUrls();
    String customerId = ensureCustomer(user);
    SessionCreateParams params =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setCustomer(customerId)
            .setSuccessUrl(stripeProperties.getSuccessUrl())
            .setCancelUrl(stripeProperties.getCancelUrl())
            .setClientReferenceId(String.valueOf(user.getId()))
            .addLineItem(
                SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
            .build();
    Session session = Session.create(params, requestOptions());
    return session.getUrl();
  }

  /**
   * Creates a Stripe Customer Portal session for the given user and returns the portal URL.
   */
  public String createPortalSession(User user) throws StripeException {
    Objects.requireNonNull(user, "user must not be null");
    requireConfigured();
    if (stripeProperties.getPortalReturnUrl() == null
        || stripeProperties.getPortalReturnUrl().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Stripe portal-return-url is not configured");
    }
    String customerId = ensureCustomer(user);
    com.stripe.param.billingportal.SessionCreateParams params =
        com.stripe.param.billingportal.SessionCreateParams.builder()
            .setCustomer(customerId)
            .setReturnUrl(stripeProperties.getPortalReturnUrl())
            .build();
    com.stripe.model.billingportal.Session session =
        com.stripe.model.billingportal.Session.create(params, requestOptions());
    return session.getUrl();
  }

  private RequestOptions requestOptions() {
    return RequestOptions.builder().setApiKey(stripeProperties.getSecretKey()).build();
  }

  /**
   * Verifies the signature of an incoming Stripe webhook payload against the configured webhook
   * secret and returns the parsed {@link Event}. Throws {@link ResponseStatusException} with HTTP
   * 400 when the signature is missing/invalid, and HTTP 503 when no webhook secret is configured
   * on this deployment.
   *
   * @param payload raw request body exactly as delivered by Stripe; must not be pre-parsed
   * @param sigHeader value of the {@code Stripe-Signature} HTTP header
   */
  public Event constructWebhookEvent(String payload, String sigHeader) {
    String secret = stripeProperties.getWebhookSecret();
    if (secret == null || secret.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Stripe webhook secret is not configured on this deployment");
    }
    if (payload == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing webhook payload");
    }
    if (sigHeader == null || sigHeader.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Missing Stripe-Signature header");
    }
    try {
      return Webhook.constructEvent(payload, sigHeader, secret);
    } catch (SignatureVerificationException ex) {
      log.warn(
          "Rejected Stripe webhook: signature verification failed ({})", ex.getMessage());
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid Stripe webhook signature");
    }
  }

  private void requireConfigured() {
    if (!stripeProperties.isConfigured()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Stripe billing is not configured on this deployment");
    }
  }

  private void requireReturnUrls() {
    if (stripeProperties.getSuccessUrl() == null
        || stripeProperties.getSuccessUrl().isBlank()
        || stripeProperties.getCancelUrl() == null
        || stripeProperties.getCancelUrl().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Stripe success-url / cancel-url are not configured");
    }
  }
}
