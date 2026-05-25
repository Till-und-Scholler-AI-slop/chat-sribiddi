package com.rft.chat_sribiddi.billing;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds a reserved slot in the daily managed-call counter. Call {@link #commit()} after a
 * successful provider call, or {@link #rollback()} (or try-with-resources) when the call fails
 * or is cancelled before completion.
 */
public final class ManagedQuotaReservation implements AutoCloseable {

  private final DailyQuotaCounterRepository repository;
  private final long internalUserId;
  private final LocalDate day;
  private final AtomicBoolean settled = new AtomicBoolean(false);

  ManagedQuotaReservation(
      DailyQuotaCounterRepository repository, long internalUserId, LocalDate day) {
    this.repository = repository;
    this.internalUserId = internalUserId;
    this.day = day;
  }

  /** No-op reservation when daily cap is disabled or the user has no persisted record. */
  public static ManagedQuotaReservation noop() {
    return new ManagedQuotaReservation(null, -1L, null);
  }

  boolean isNoop() {
    return repository == null;
  }

  /** Keeps the reserved counter increment (successful managed call). */
  public void commit() {
    if (isNoop() || !settled.compareAndSet(false, true)) {
      return;
    }
  }

  /** Releases the reserved slot by decrementing the counter. */
  public void rollback() {
    if (isNoop() || !settled.compareAndSet(false, true)) {
      return;
    }
    repository.decrementCounter(internalUserId, day);
  }

  @Override
  public void close() {
    rollback();
  }
}
