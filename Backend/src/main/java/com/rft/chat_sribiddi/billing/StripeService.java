package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
   */
  public String ensureCustomer(User user) throws StripeException {
    Objects.requireNonNull(user, "user must not be null");
    requireConfigured();
    String existing = user.getStripeCustomerId();
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    CustomerCreateParams params =
        CustomerCreateParams.builder()
            .setEmail(user.getEmail())
            .putMetadata("user_id", String.valueOf(user.getId()))
            .putMetadata("provider_id", user.getProviderId() == null ? "" : user.getProviderId())
            .build();
    Customer customer = Customer.create(params, requestOptions());
    user.setStripeCustomerId(customer.getId());
    userRepository.save(user);
    log.debug("Created Stripe customer {} for user id={}", customer.getId(), user.getId());
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
