package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void doFilterInternal_returns429AfterAnonymousLimit() throws ServletException, IOException {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setAnonymousRequestsPerMinute(1);
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(properties, java.time.Clock.systemUTC());
    RateLimitFilter filter = new RateLimitFilter(properties, limiter);

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
  void rateLimitForCurrentRequest_usesAuthenticatedLimit() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setAuthenticatedRequestsPerMinute(777);
    RateLimitFilter filter =
        new RateLimitFilter(properties, (key, limit) -> RateLimitDecision.allow());
    TestingAuthenticationToken authentication = new TestingAuthenticationToken("provider-1", "n/a");
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    RateLimitFilter.RateLimit rateLimit =
        filter.rateLimitForCurrentRequest(new MockHttpServletRequest("GET", "/chats"));

    assertThat(rateLimit.key()).isEqualTo("user:provider-1");
    assertThat(rateLimit.limit()).isEqualTo(777);
  }

  @Test
  void clientIp_trustsForwardedForOnlyFromTrustedProxy() {
    RateLimitProperties properties = new RateLimitProperties();
    RateLimitFilter filter =
        new RateLimitFilter(properties, (key, limit) -> RateLimitDecision.allow());

    MockHttpServletRequest trustedProxyRequest = new MockHttpServletRequest("GET", "/chats");
    trustedProxyRequest.setRemoteAddr("127.0.0.1");
    trustedProxyRequest.addHeader("X-Forwarded-For", "198.51.100.42, 10.0.0.2");

    MockHttpServletRequest directRequest = new MockHttpServletRequest("GET", "/chats");
    directRequest.setRemoteAddr("198.51.100.7");
    directRequest.addHeader("X-Forwarded-For", "198.51.100.42");

    assertThat(filter.clientIp(trustedProxyRequest)).isEqualTo("198.51.100.42");
    assertThat(filter.clientIp(directRequest)).isEqualTo("198.51.100.7");
  }

  @Test
  void shouldNotFilter_doesNotSkipRemovedWebhookPath() throws ServletException {
    RateLimitProperties properties = new RateLimitProperties();
    RateLimitFilter filter =
        new RateLimitFilter(properties, (key, limit) -> RateLimitDecision.allow());

    assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/removed-webhook")))
        .isFalse();
  }
}
