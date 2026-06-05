package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/usage")
public class UsageController {

  static final Duration USAGE_WINDOW = Duration.ofDays(30);

  private final UserRepository userRepository;
  private final UsageEventRepository usageEventRepository;
  private final RequestUserResolver requestUserResolver;
  private final Clock clock;

  @Autowired
  public UsageController(
      UserRepository userRepository,
      UsageEventRepository usageEventRepository,
      RequestUserResolver requestUserResolver) {
    this(
        userRepository,
        usageEventRepository,
        requestUserResolver,
        Clock.system(ZoneId.of("UTC")));
  }

  UsageController(
      UserRepository userRepository,
      UsageEventRepository usageEventRepository,
      RequestUserResolver requestUserResolver,
      Clock clock) {
    this.userRepository = userRepository;
    this.usageEventRepository = usageEventRepository;
    this.requestUserResolver = requestUserResolver;
    this.clock = clock;
  }

  @GetMapping
  public ResponseEntity<UsageResponse> usage(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String model) {
    User user = requireUser(principal, request);
    Instant now = clock.instant();
    Instant until = parseUsageTo(to, now);
    Instant since = parseUsageFrom(from, until.minus(USAGE_WINDOW));
    if (!since.isBefore(until)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
    }

    String modelFilter = normalizeModelFilter(model);
    List<UsageEventEntity> events =
        modelFilter == null
            ? usageEventRepository.findForDashboard(user.getId(), since, until)
            : usageEventRepository.findForDashboardByModel(user.getId(), since, until, modelFilter);
    List<UsageItem> items = aggregateByModel(events);
    List<DailyUsageItem> daily = aggregateByDay(events);
    long totalCalls = items.stream().mapToLong(UsageItem::calls).sum();
    long totalTokens = items.stream().mapToLong(UsageItem::totalTokens).sum();
    long totalCostMicroCents = items.stream().mapToLong(UsageItem::costMicroCents).sum();
    return ResponseEntity.ok(
        new UsageResponse(
            since, until, totalCalls, totalTokens, totalCostMicroCents, items, daily));
  }

  private User requireUser(OidcUser principal, HttpServletRequest request) {
    String providerId =
        requestUserResolver
            .resolveUserId(principal, request)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    return userRepository
        .findByProviderId(providerId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
  }

  private static List<UsageItem> aggregateByModel(List<UsageEventEntity> events) {
    Map<ModelUsageKey, UsageAccumulator> byModel = new LinkedHashMap<>();
    for (UsageEventEntity event : events) {
      ModelUsageKey key = new ModelUsageKey(event.getProvider(), event.getModelId());
      byModel.computeIfAbsent(key, _ -> new UsageAccumulator()).add(event);
    }
    return byModel.entrySet().stream()
        .map(
            entry ->
                new UsageItem(
                    entry.getKey().provider(),
                    entry.getKey().modelId(),
                    entry.getValue().calls,
                    entry.getValue().promptTokens,
                    entry.getValue().completionTokens,
                    entry.getValue().totalTokens,
                    entry.getValue().costMicroCents))
        .sorted((left, right) -> Long.compare(right.calls(), left.calls()))
        .toList();
  }

  private static List<DailyUsageItem> aggregateByDay(List<UsageEventEntity> events) {
    Map<LocalDate, UsageAccumulator> byDay = new TreeMap<>();
    for (UsageEventEntity event : events) {
      if (event.getCreatedAt() == null) {
        continue;
      }
      LocalDate day = LocalDate.ofInstant(event.getCreatedAt(), ZoneOffset.UTC);
      byDay.computeIfAbsent(day, _ -> new UsageAccumulator()).add(event);
    }
    return byDay.entrySet().stream()
        .map(
            entry ->
                new DailyUsageItem(
                    entry.getKey(),
                    entry.getValue().calls,
                    entry.getValue().totalTokens,
                    entry.getValue().costMicroCents))
        .toList();
  }

  private static Instant parseUsageFrom(String value, Instant fallback) {
    return parseUsageInstant(value, fallback, false);
  }

  private static Instant parseUsageTo(String value, Instant fallback) {
    return parseUsageInstant(value, fallback, true);
  }

  private static Instant parseUsageInstant(String value, Instant fallback, boolean dateEndExclusive) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String trimmed = value.trim();
    try {
      return Instant.parse(trimmed);
    } catch (RuntimeException ignored) {
      try {
        LocalDate date = LocalDate.parse(trimmed);
        LocalDate adjusted = dateEndExclusive ? date.plusDays(1) : date;
        return adjusted.atStartOfDay(ZoneOffset.UTC).toInstant();
      } catch (RuntimeException ex) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Invalid usage date parameter: " + value);
      }
    }
  }

  private static String normalizeModelFilter(String model) {
    return model == null || model.isBlank() ? null : model.trim();
  }

  private record ModelUsageKey(Provider provider, String modelId) {}

  private static final class UsageAccumulator {
    private long calls;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long costMicroCents;

    void add(UsageEventEntity event) {
      calls++;
      promptTokens += safeLong(event.getPromptTokens());
      completionTokens += safeLong(event.getCompletionTokens());
      totalTokens += totalTokens(event);
      costMicroCents += safeLong(event.getCostMicroCents());
    }
  }

  private static long totalTokens(UsageEventEntity event) {
    Long total = event.getTotalTokens();
    if (total != null) {
      return Math.max(0L, total);
    }
    return safeLong(event.getPromptTokens()) + safeLong(event.getCompletionTokens());
  }

  private static long safeLong(Long value) {
    return value == null ? 0L : Math.max(0L, value);
  }

  public record UsageResponse(
      Instant since,
      Instant until,
      long totalCalls,
      long totalTokens,
      long totalCostMicroCents,
      List<UsageItem> items,
      List<DailyUsageItem> daily) {}

  public record UsageItem(
      Provider provider,
      String modelId,
      long calls,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long costMicroCents) {}

  public record DailyUsageItem(LocalDate day, long calls, long totalTokens, long costMicroCents) {}
}
