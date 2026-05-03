package com.rft.chat_sribiddi.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis", matchIfMissing = true)
public class RedisBucketRateLimiter implements RateLimiter {

  private static final String KEY_PREFIX = "rate-limit:";

  private final ProxyManager<String> proxyManager;
  private final RateLimitProperties properties;

  @Override
  public RateLimitDecision tryConsume(String key, int requestsPerMinute) {
    try {
      Bucket bucket =
          proxyManager.getProxy(KEY_PREFIX + key, () -> configuration(requestsPerMinute));
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      if (probe.isConsumed()) {
        return RateLimitDecision.allow();
      }
      return RateLimitDecision.denied(
          Math.ceilDiv(probe.getNanosToWaitForRefill(), Duration.ofSeconds(1).toNanos()));
    } catch (RuntimeException e) {
      log.warn("Redis rate limit check failed closed for key '{}': {}", key, e.getMessage());
      return RateLimitDecision.denied(properties.getRedisFailureRetryAfterSeconds());
    }
  }

  private BucketConfiguration configuration(int requestsPerMinute) {
    return BucketConfiguration.builder()
        .addLimit(
            limit ->
                limit.capacity(requestsPerMinute)
                    .refillGreedy(requestsPerMinute, Duration.ofMinutes(1)))
        .build();
  }
}
