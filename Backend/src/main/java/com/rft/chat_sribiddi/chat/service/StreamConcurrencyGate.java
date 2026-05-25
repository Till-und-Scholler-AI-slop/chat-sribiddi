package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tracks in-flight chat streams per user so parallel stream limits from entitlements can be
 * enforced before a new stream starts.
 */
@Component
@RequiredArgsConstructor
public class StreamConcurrencyGate {

  private final EntitlementService entitlementService;
  private final ConcurrentHashMap<String, AtomicInteger> activeStreams = new ConcurrentHashMap<>();

  /**
   * Increments the active-stream counter for {@code userId}. Throws when the user already has
   * {@code parallelStreams} (or more) in-flight streams.
   */
  public void acquire(String userId) {
    Entitlements entitlements = entitlementService.get(userId);
    int limit = entitlements.parallelStreams();
    if (limit <= 0) {
      return;
    }
    AtomicInteger counter =
        activeStreams.computeIfAbsent(userId, _ -> new AtomicInteger(0));
    int current = counter.incrementAndGet();
    if (current > limit) {
      counter.decrementAndGet();
      if (counter.get() <= 0) {
        activeStreams.remove(userId, counter);
      }
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.PARALLEL_STREAMS_EXCEEDED,
          entitlements.plan(),
          "Parallel stream limit (%d) reached for plan %s".formatted(limit, entitlements.plan()));
    }
  }

  /** Decrements the active-stream counter; safe to call when no stream was acquired. */
  public void release(String userId) {
    AtomicInteger counter = activeStreams.get(userId);
    if (counter == null) {
      return;
    }
    int remaining = counter.decrementAndGet();
    if (remaining <= 0) {
      activeStreams.remove(userId, counter);
    }
  }
}
