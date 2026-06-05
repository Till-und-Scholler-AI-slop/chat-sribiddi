package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class InMemoryRateLimitFilterTest {

  @Test
  void springContextInstantiatesFilterUsingInjectedPropertiesConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(RateLimitProperties.class, InMemoryRateLimitFilter.class);
      context.getDefaultListableBeanFactory().registerSingleton("clock", Clock.systemUTC());
      context.refresh();

      InMemoryRateLimitFilter filter = context.getBean(InMemoryRateLimitFilter.class);
      RateLimitProperties injectedProperties = context.getBean(RateLimitProperties.class);

      assertThat(filter).isNotNull();
      assertThat(ReflectionTestUtils.getField(filter, "properties")).isSameAs(injectedProperties);
    }
  }

  @Test
  void doFilterInternal_returns429AfterAnonymousLimit() throws ServletException, IOException {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setAnonymousRequestsPerMinute(1);
    var filter =
        new InMemoryRateLimitFilter(
            properties, Clock.fixed(Instant.parse("2026-04-29T12:00:00Z"), ZoneOffset.UTC));

    MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/chats");
    firstRequest.setRemoteAddr("203.0.113.10");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    filter.doFilterInternal(firstRequest, firstResponse, new MockFilterChain());

    MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/chats");
    secondRequest.setRemoteAddr("203.0.113.10");
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    filter.doFilterInternal(secondRequest, secondResponse, new MockFilterChain());

    assertThat(firstResponse.getStatus()).isEqualTo(200);
    assertThat(secondResponse.getStatus()).isEqualTo(429);
    assertThat(secondResponse.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
  }

  @Test
  void shouldNotFilter_doesNotSkipRemovedWebhookPath() throws ServletException {
    RateLimitProperties properties = new RateLimitProperties();
    var filter =
        new InMemoryRateLimitFilter(
            properties, Clock.fixed(Instant.parse("2026-04-29T12:00:00Z"), ZoneOffset.UTC));

    assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/removed-webhook")))
        .isFalse();
  }
}
