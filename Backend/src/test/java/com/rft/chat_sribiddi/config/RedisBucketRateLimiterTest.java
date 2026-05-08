package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RedisBucketRateLimiterTest {

  @Test
  void tryConsume_failsClosedWhenRedisProxyFails() {
    @SuppressWarnings("unchecked")
    ProxyManager<String> proxyManager = mock(ProxyManager.class);
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRedisFailureRetryAfterSeconds(37);
    when(proxyManager.getProxy(eq("rate-limit:user:provider-1"), any(Supplier.class)))
        .thenThrow(new IllegalStateException("redis down"));

    RateLimitDecision decision =
        new RedisBucketRateLimiter(proxyManager, properties).tryConsume("user:provider-1", 30);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isEqualTo(37);
  }
}
