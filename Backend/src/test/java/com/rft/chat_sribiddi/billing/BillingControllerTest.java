package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.billing.BillingController.BillingMeResponse;
import com.rft.chat_sribiddi.billing.BillingController.BillingUsageResponse;
import com.rft.chat_sribiddi.billing.BillingController.CheckoutSessionRequest;
import com.rft.chat_sribiddi.billing.BillingController.UrlResponse;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingControllerTest {

  private static final String PROVIDER_ID = "user-xyz";
  private static final Instant NOW = Instant.parse("2026-04-20T12:00:00Z");

  @Mock private StripeService stripeService;
  @Mock private UserRepository userRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private UsageEventRepository usageEventRepository;
  @Mock private StripeWebhookProcessor stripeWebhookProcessor;
  @Mock private HttpServletRequest request;

  private BillingController controller;
  private StripeProperties stripeProperties;
  private User user;

  @BeforeEach
  void setUp() {
    stripeProperties = new StripeProperties();
    stripeProperties.setPriceIdPremiumMonth("price_premium_month");
    stripeProperties.setPriceIdPremiumYear("price_premium_year");
    stripeProperties.setPriceIdMaxMonth("price_max_month");
    stripeProperties.setPriceIdMaxYear("price_max_year");

    user = new User();
    user.setEmail("user@example.com");
    user.setProviderId(PROVIDER_ID);
    user.setPlan(Plan.PREMIUM);
    user.setBillingModePreference(BillingMode.AUTO);
    setId(user, 42L);

    RequestUserResolver resolver = new RequestUserResolver();
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn(PROVIDER_ID);
    when(userRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.of(user));

    controller =
        new BillingController(
            stripeService,
            stripeProperties,
            userRepository,
            subscriptionRepository,
            usageEventRepository,
            resolver,
            stripeWebhookProcessor,
            Clock.fixed(NOW, ZoneId.of("UTC")));
  }

  @Test
  void createCheckoutSession_returnsUrlFromStripeService() throws Exception {
    when(stripeService.createCheckoutSession(eq(user), eq("price_premium_month")))
        .thenReturn("https://checkout.stripe.test/abc");

    UrlResponse body =
        controller
            .createCheckoutSession(
                null, request, new CheckoutSessionRequest(Plan.PREMIUM, StripeProperties.Interval.MONTH))
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.url()).isEqualTo("https://checkout.stripe.test/abc");
  }

  @Test
  void createCheckoutSession_rejectsFreePlan() {
    assertThatThrownBy(
            () ->
                controller.createCheckoutSession(
                    null,
                    request,
                    new CheckoutSessionRequest(Plan.FREE, StripeProperties.Interval.MONTH)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void createCheckoutSession_requiresPlanAndInterval() {
    assertThatThrownBy(() -> controller.createCheckoutSession(null, request, null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void createPortalSession_returnsUrlFromStripeService() throws Exception {
    when(stripeService.createPortalSession(user))
        .thenReturn("https://billing.stripe.test/portal/abc");

    UrlResponse body = controller.createPortalSession(null, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.url()).isEqualTo("https://billing.stripe.test/portal/abc");
  }

  @Test
  void me_returnsSubscriptionFieldsWhenPresent() {
    SubscriptionEntity sub = new SubscriptionEntity();
    sub.setStatus("active");
    sub.setCurrentPeriodEnd(NOW.plusSeconds(86_400));
    sub.setCancelAtPeriodEnd(true);
    when(subscriptionRepository.findFirstByUserIdOrderByUpdatedAtDesc(42L))
        .thenReturn(Optional.of(sub));

    BillingMeResponse body = controller.me(null, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.plan()).isEqualTo(Plan.PREMIUM);
    assertThat(body.status()).isEqualTo("active");
    assertThat(body.periodEnd()).isEqualTo(NOW.plusSeconds(86_400));
    assertThat(body.cancelAtPeriodEnd()).isTrue();
    assertThat(body.billingModePreference()).isEqualTo(BillingMode.AUTO);
  }

  @Test
  void me_fallsBackToUserFieldsWhenNoSubscription() {
    user.setPlanStatus("past_due");
    user.setPlanValidUntil(NOW.plusSeconds(100));
    when(subscriptionRepository.findFirstByUserIdOrderByUpdatedAtDesc(42L))
        .thenReturn(Optional.empty());

    BillingMeResponse body = controller.me(null, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo("past_due");
    assertThat(body.periodEnd()).isEqualTo(NOW.plusSeconds(100));
    assertThat(body.cancelAtPeriodEnd()).isFalse();
  }

  @Test
  void usage_aggregatesEventsFromTrailing30Days() {
    UsageEventRepository.UsageAggregate agg =
        new UsageEventRepository.UsageAggregate() {
          @Override
          public Provider getProvider() {
            return Provider.OPENROUTER;
          }

          @Override
          public String getModelId() {
            return "openai/gpt-4o-mini";
          }

          @Override
          public long getCalls() {
            return 3L;
          }

          @Override
          public long getPromptTokens() {
            return 100L;
          }

          @Override
          public long getCompletionTokens() {
            return 200L;
          }

          @Override
          public long getTotalTokens() {
            return 300L;
          }

          @Override
          public long getCostMicroCents() {
            return 4_500L;
          }
        };
    when(usageEventRepository.aggregateSince(
            eq(42L), eq(NOW.minus(BillingController.USAGE_WINDOW))))
        .thenReturn(List.of(agg));

    BillingUsageResponse body = controller.usage(null, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.since()).isEqualTo(NOW.minus(BillingController.USAGE_WINDOW));
    assertThat(body.until()).isEqualTo(NOW);
    assertThat(body.totalCalls()).isEqualTo(3L);
    assertThat(body.totalTokens()).isEqualTo(300L);
    assertThat(body.totalCostMicroCents()).isEqualTo(4_500L);
    assertThat(body.items()).hasSize(1);
    assertThat(body.items().get(0).modelId()).isEqualTo("openai/gpt-4o-mini");
    assertThat(body.items().get(0).calls()).isEqualTo(3L);
  }

  @Test
  void requireUser_returns401WhenUnauthenticated() {
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn(null);

    assertThatThrownBy(() -> controller.me(null, request))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private static void setId(User user, long id) {
    try {
      java.lang.reflect.Field f = User.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
