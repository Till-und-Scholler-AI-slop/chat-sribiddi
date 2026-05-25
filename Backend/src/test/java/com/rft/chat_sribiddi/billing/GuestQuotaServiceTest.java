package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.config.RateLimitProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class GuestQuotaServiceTest {

  @Test
  void springContextInstantiatesServiceWithProductionConstructor() {
    BillingProperties billingProperties = validBillingProperties();
    new ApplicationContextRunner()
        .withBean(BillingProperties.class, () -> billingProperties)
        .withBean(RateLimitProperties.class)
        .withBean(GuestQuotaService.class)
        .run(context -> assertThat(context).hasSingleBean(GuestQuotaService.class));
  }

  private static BillingProperties validBillingProperties() {
    BillingProperties billingProperties = new BillingProperties();
    billingProperties.setGuestDailyCap(20);
    for (Plan plan : Plan.values()) {
      BillingProperties.TierConfig tier = new BillingProperties.TierConfig();
      tier.setDailyCap(100);
      billingProperties.getTiers().put(plan, tier);
    }
    return billingProperties;
  }

  @Test
  void assertWithinDailyCap_blocksAfterConfiguredAnonymousCap() {
    BillingProperties billingProperties = new BillingProperties();
    billingProperties.setGuestDailyCap(2);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            billingProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");
    request.setRemoteAddr("203.0.113.10");

    service.assertWithinDailyCap(request, "guest-1");
    service.incrementCounter(request, "guest-1");
    service.assertWithinDailyCap(request, "guest-1");
    service.incrementCounter(request, "guest-1");

    assertThatThrownBy(() -> service.assertWithinDailyCap(request, "guest-1"))
        .isInstanceOf(PlanRestrictionException.class)
        .extracting("reason")
        .isEqualTo(PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }

  @Test
  void assertWithinDailyCap_enforcesIpCapWhenGuestIdChanges() {
    BillingProperties billingProperties = new BillingProperties();
    billingProperties.setGuestDailyCap(1);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            billingProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");
    request.setRemoteAddr("203.0.113.10");

    service.incrementCounter(request, "guest-1");

    assertThatThrownBy(() -> service.assertWithinDailyCap(request, "guest-2"))
        .isInstanceOf(PlanRestrictionException.class)
        .extracting("reason")
        .isEqualTo(PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }

  @Test
  void reservationRollback_releasesReservedIpAndGuestSlots() {
    BillingProperties billingProperties = new BillingProperties();
    billingProperties.setGuestDailyCap(1);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            billingProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");
    request.setRemoteAddr("203.0.113.10");

    GuestQuotaService.GuestQuotaReservation reservation =
        service.reserveDailyCall(request, "guest-1");
    assertThatThrownBy(() -> service.reserveDailyCall(request, "guest-2"))
        .isInstanceOf(PlanRestrictionException.class);

    reservation.rollback();

    assertThatNoException().isThrownBy(() -> service.reserveDailyCall(request, "guest-2").commit());
  }

  @Test
  void assertWithinDailyCap_failsClosedWhenRedisBackendHasNoTemplate() {
    BillingProperties billingProperties = new BillingProperties();
    billingProperties.setGuestDailyCap(20);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.REDIS);
    GuestQuotaService service =
        new GuestQuotaService(
            billingProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");

    assertThatThrownBy(() -> service.assertWithinDailyCap(request, "guest-1"))
        .isInstanceOf(PlanRestrictionException.class)
        .extracting("reason")
        .isEqualTo(PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }
}
