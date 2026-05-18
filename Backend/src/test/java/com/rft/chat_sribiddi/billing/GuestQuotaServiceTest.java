package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.config.RateLimitProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GuestQuotaServiceTest {

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
  void assertWithinDailyCap_usesGuestIdAndIpInMemoryKey() {
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

    service.assertWithinDailyCap(request, "guest-2");
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
