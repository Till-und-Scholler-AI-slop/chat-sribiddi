package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GuestQuotaService {

  private static final String KEY_PREFIX = "guest-quota:";
  private static final ZoneId UTC = ZoneId.of("UTC");

  private final BillingProperties billingProperties;
  private final RateLimitProperties rateLimitProperties;
  private final StringRedisTemplate redisTemplate;
  private final Clock clock;
  private final Map<String, MemoryCounter> memoryCounters = new ConcurrentHashMap<>();

  public GuestQuotaService(
      BillingProperties billingProperties,
      RateLimitProperties rateLimitProperties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    this(
        billingProperties,
        rateLimitProperties,
        redisTemplateProvider.getIfAvailable(),
        Clock.system(UTC));
  }

  GuestQuotaService(
      BillingProperties billingProperties,
      RateLimitProperties rateLimitProperties,
      StringRedisTemplate redisTemplate,
      Clock clock) {
    this.billingProperties = billingProperties;
    this.rateLimitProperties = rateLimitProperties;
    this.redisTemplate = redisTemplate;
    this.clock = clock;
  }

  public void assertWithinDailyCap(HttpServletRequest request, String guestId) {
    long cap = billingProperties.getGuestDailyCap();
    if (cap <= 0) {
      return;
    }
    for (String key : keys(request, guestId)) {
      long current = currentCount(key);
      if (current >= cap) {
        throw dailyCapReached(cap);
      }
    }
  }

  public void incrementCounter(HttpServletRequest request, String guestId) {
    reserveDailyCall(request, guestId).commit();
  }

  public GuestQuotaReservation reserveDailyCall(HttpServletRequest request, String guestId) {
    long cap = billingProperties.getGuestDailyCap();
    if (cap <= 0) {
      return GuestQuotaReservation.noop();
    }
    List<String> reservedKeys = new ArrayList<>();
    try {
      for (String key : keys(request, guestId)) {
        reserveKey(key, cap);
        reservedKeys.add(key);
      }
      return new GuestQuotaReservation(this, reservedKeys);
    } catch (RuntimeException ex) {
      rollbackKeys(reservedKeys);
      throw ex;
    }
  }

  private void reserveKey(String key, long cap) {
    if (useRedis()) {
      if (redisTemplate == null) {
        throw quotaUnavailable();
      }
      try {
        Long next = redisTemplate.opsForValue().increment(key);
        if (next != null && next == 1L) {
          redisTemplate.expire(key, Duration.ofHours(36));
        }
        if (next == null) {
          throw quotaUnavailable();
        }
        if (next > cap) {
          rollbackKeys(List.of(key));
          throw dailyCapReached(cap);
        }
        return;
      } catch (RuntimeException ex) {
        log.warn("Redis guest quota increment failed for key '{}': {}", key, ex.getMessage());
        if (ex instanceof PlanRestrictionException) {
          throw ex;
        }
        throw quotaUnavailable();
      }
    }

    LocalDate today = today();
    AtomicBoolean blocked = new AtomicBoolean(false);
    memoryCounters.compute(
        key,
        (_key, counter) -> {
          if (counter == null || !counter.day().equals(today)) {
            return new MemoryCounter(today, 1L);
          }
          if (counter.count() >= cap) {
            blocked.set(true);
            return counter;
          }
          return new MemoryCounter(today, counter.count() + 1L);
        });
    if (blocked.get()) {
      throw dailyCapReached(cap);
    }
  }

  private long currentCount(String key) {
    if (useRedis()) {
      if (redisTemplate == null) {
        throw quotaUnavailable();
      }
      try {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
          return 0L;
        }
        return Long.parseLong(raw);
      } catch (RuntimeException ex) {
        log.warn("Redis guest quota check failed for key '{}': {}", key, ex.getMessage());
        throw quotaUnavailable();
      }
    }

    LocalDate today = today();
    memoryCounters.entrySet().removeIf(entry -> !entry.getValue().day().equals(today));
    MemoryCounter counter = memoryCounters.get(key);
    return counter == null || !counter.day().equals(today) ? 0L : counter.count();
  }

  private boolean useRedis() {
    return rateLimitProperties.getBackend() == RateLimitProperties.Backend.REDIS;
  }

  private void rollbackKeys(List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    if (useRedis()) {
      if (redisTemplate == null) {
        return;
      }
      for (String key : keys) {
        try {
          redisTemplate.opsForValue().decrement(key);
        } catch (RuntimeException ex) {
          log.warn("Redis guest quota rollback failed for key '{}': {}", key, ex.getMessage());
        }
      }
      return;
    }

    for (String key : keys) {
      memoryCounters.computeIfPresent(
          key,
          (_key, counter) ->
              counter.count() <= 1 ? null : new MemoryCounter(counter.day(), counter.count() - 1));
    }
  }

  private PlanRestrictionException dailyCapReached(long cap) {
    return new PlanRestrictionException(
        PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED,
        Plan.FREE,
        "Daily guest call cap (%d) reached".formatted(cap));
  }

  private PlanRestrictionException quotaUnavailable() {
    return new PlanRestrictionException(
        PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED,
        Plan.FREE,
        "Guest quota is temporarily unavailable. Please retry later.");
  }

  private List<String> keys(HttpServletRequest request, String guestId) {
    String today = today().toString();
    String clientIp = clientIp(request);
    String normalizedGuestId = normalizeGuestId(guestId);
    return List.of(
        KEY_PREFIX + sha256(today + ":ip:" + clientIp),
        KEY_PREFIX + sha256(today + ":ip-guest:" + clientIp + ":" + normalizedGuestId));
  }

  private String clientIp(HttpServletRequest request) {
    if (request == null) {
      return "unknown";
    }
    String remoteAddress = request.getRemoteAddr();
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (rateLimitProperties.getTrustedProxies().contains(remoteAddress)
        && forwardedFor != null
        && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return remoteAddress;
  }

  private String normalizeGuestId(String guestId) {
    return guestId == null || guestId.isBlank() ? "anonymous" : guestId.trim();
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }

  public static final class GuestQuotaReservation {
    private static final GuestQuotaReservation NOOP = new GuestQuotaReservation(null, List.of());

    private final GuestQuotaService service;
    private final List<String> reservedKeys;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private GuestQuotaReservation(GuestQuotaService service, List<String> reservedKeys) {
      this.service = service;
      this.reservedKeys = List.copyOf(reservedKeys);
    }

    static GuestQuotaReservation noop() {
      return NOOP;
    }

    public void commit() {
      closed.compareAndSet(false, true);
    }

    public void rollback() {
      if (service != null && closed.compareAndSet(false, true)) {
        service.rollbackKeys(reservedKeys);
      }
    }
  }

  private record MemoryCounter(LocalDate day, long count) {}
}
