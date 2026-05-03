package com.rft.chat_sribiddi.config;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "memory")
public class InMemoryRateLimiter implements RateLimiter {

  private final RateLimitProperties properties;
  private final Clock clock;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public InMemoryRateLimiter(RateLimitProperties properties) {
    this(properties, Clock.systemUTC());
  }

  InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public RateLimitDecision tryConsume(String key, int requestsPerMinute) {
    cleanupExpiredBuckets();
    Bucket bucket =
        buckets.computeIfAbsent(key, ignored -> new Bucket(requestsPerMinute, clock.millis()));
    long retryAfterSeconds = bucket.tryConsume(requestsPerMinute, clock.millis());
    return retryAfterSeconds > 0
        ? RateLimitDecision.denied(retryAfterSeconds)
        : RateLimitDecision.allow();
  }

  private void cleanupExpiredBuckets() {
    long now = clock.millis();
    long ttlMillis = properties.getBucketTtl().toMillis();
    buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMillis() > ttlMillis);
  }

  private static final class Bucket {
    private long windowStartedMillis;
    private long lastSeenMillis;
    private int remaining;

    private Bucket(int limit, long nowMillis) {
      this.windowStartedMillis = nowMillis;
      this.lastSeenMillis = nowMillis;
      this.remaining = limit;
    }

    synchronized long tryConsume(int limit, long nowMillis) {
      lastSeenMillis = nowMillis;
      long elapsedMillis = nowMillis - windowStartedMillis;
      if (elapsedMillis >= 60_000) {
        windowStartedMillis = nowMillis;
        remaining = limit;
      }
      if (remaining <= 0) {
        long retryAfterMillis = Math.max(1, 60_000 - elapsedMillis);
        return Math.ceilDiv(retryAfterMillis, 1000);
      }
      remaining--;
      return 0;
    }

    long lastSeenMillis() {
      return lastSeenMillis;
    }
  }
}
