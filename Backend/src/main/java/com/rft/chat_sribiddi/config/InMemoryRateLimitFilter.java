package com.rft.chat_sribiddi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Legacy in-memory servlet filter kept for focused unit coverage. Runtime rate limiting is handled
 * by {@link RateLimitFilter}, which delegates to either Redis or in-memory {@link RateLimiter}
 * implementations based on {@code app.rate-limit.backend}; keeping this class out of component
 * scanning prevents double rate limiting.
 */
@RequiredArgsConstructor
public class InMemoryRateLimitFilter extends OncePerRequestFilter {

  private final RateLimitProperties properties;
  private final Clock clock;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !properties.isEnabled()
        || "OPTIONS".equalsIgnoreCase(request.getMethod())
        || "/actuator/health".equals(path)
        || "/actuator/info".equals(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    cleanupExpiredBuckets();

    RateLimit rateLimit = rateLimitForCurrentRequest(request);
    Bucket bucket =
        buckets.computeIfAbsent(
            rateLimit.key(), ignored -> new Bucket(rateLimit.limit(), clock.millis()));

    long retryAfterSeconds = bucket.tryConsume(rateLimit.limit(), clock.millis());
    if (retryAfterSeconds > 0) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
      response.setContentType("application/json");
      response.getWriter().write("{\"message\":\"Too many requests. Please retry later.\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private RateLimit rateLimitForCurrentRequest(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.isAuthenticated()
        && !"anonymousUser".equals(authentication.getPrincipal())) {
      return new RateLimit(
          "user:" + authentication.getName(), properties.getAuthenticatedRequestsPerMinute());
    }
    return new RateLimit(
        "ip:" + clientIp(request), properties.getAnonymousRequestsPerMinute());
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private void cleanupExpiredBuckets() {
    long now = clock.millis();
    long ttlMillis = properties.getBucketTtl().toMillis();
    buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMillis() > ttlMillis);
  }

  private record RateLimit(String key, int limit) {}

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
