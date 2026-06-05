package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.access.AccessProperties;
import com.rft.chat_sribiddi.access.AccessRestrictionException;
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
    new ApplicationContextRunner()
        .withBean(AccessProperties.class, GuestQuotaServiceTest::accessProperties)
        .withBean(RateLimitProperties.class)
        .withBean(GuestQuotaService.class)
        .run(context -> assertThat(context).hasSingleBean(GuestQuotaService.class));
  }

  @Test
  void assertWithinDailyCap_blocksAfterConfiguredAnonymousCap() {
    AccessProperties accessProperties = accessProperties(2);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            accessProperties,
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
        .isInstanceOf(AccessRestrictionException.class)
        .extracting("reason")
        .isEqualTo(AccessRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }

  @Test
  void assertWithinDailyCap_enforcesIpCapWhenGuestIdChanges() {
    AccessProperties accessProperties = accessProperties(1);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            accessProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");
    request.setRemoteAddr("203.0.113.10");

    service.incrementCounter(request, "guest-1");

    assertThatThrownBy(() -> service.assertWithinDailyCap(request, "guest-2"))
        .isInstanceOf(AccessRestrictionException.class)
        .extracting("reason")
        .isEqualTo(AccessRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }

  @Test
  void reservationRollback_releasesReservedIpAndGuestSlots() {
    AccessProperties accessProperties = accessProperties(1);
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.MEMORY);
    GuestQuotaService service =
        new GuestQuotaService(
            accessProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");
    request.setRemoteAddr("203.0.113.10");

    GuestQuotaService.GuestQuotaReservation reservation =
        service.reserveDailyCall(request, "guest-1");
    assertThatThrownBy(() -> service.reserveDailyCall(request, "guest-2"))
        .isInstanceOf(AccessRestrictionException.class);

    reservation.rollback();

    assertThatNoException().isThrownBy(() -> service.reserveDailyCall(request, "guest-2").commit());
  }

  @Test
  void assertWithinDailyCap_failsClosedWhenRedisBackendHasNoTemplate() {
    AccessProperties accessProperties = accessProperties();
    RateLimitProperties rateLimitProperties = new RateLimitProperties();
    rateLimitProperties.setBackend(RateLimitProperties.Backend.REDIS);
    GuestQuotaService service =
        new GuestQuotaService(
            accessProperties,
            rateLimitProperties,
            null,
            Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anonymous/chats/stream");

    assertThatThrownBy(() -> service.assertWithinDailyCap(request, "guest-1"))
        .isInstanceOf(AccessRestrictionException.class)
        .extracting("reason")
        .isEqualTo(AccessRestrictionException.Reason.DAILY_CAP_EXCEEDED);
  }

  private static AccessProperties accessProperties() {
    return accessProperties(20);
  }

  private static AccessProperties accessProperties(long guestDailyCap) {
    AccessProperties accessProperties = new AccessProperties();
    accessProperties.setGuestDailyCap(guestDailyCap);
    return accessProperties;
  }
}
