package com.rft.chat_sribiddi.billing;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin data-access helper for the {@code daily_quota_counters} table. Uses a native
 * {@code INSERT ... ON CONFLICT ... DO UPDATE} UPSERT so that concurrent increments on the same
 * (user_id, day) row are atomic without requiring a prior read.
 */
@Repository
@RequiredArgsConstructor
public class DailyQuotaCounterRepository {

  private static final String UPSERT_INCREMENT_SQL =
      """
      INSERT INTO daily_quota_counters (user_id, day, managed_calls_count)
      VALUES (?, ?, 1)
      ON CONFLICT (user_id, day)
      DO UPDATE SET managed_calls_count = daily_quota_counters.managed_calls_count + 1
      """;

  private static final String SELECT_COUNT_SQL =
      "SELECT managed_calls_count FROM daily_quota_counters WHERE user_id = ? AND day = ?";

  private final JdbcTemplate jdbcTemplate;

  /**
   * Atomically increments (or inserts with value 1) the managed-calls counter for the given user
   * on the given day.
   */
  @Transactional
  public void incrementCounter(long userId, LocalDate day) {
    jdbcTemplate.update(UPSERT_INCREMENT_SQL, userId, day);
  }

  /** Returns the current managed-calls count for the user on the given day, or 0 if none. */
  @Transactional(readOnly = true)
  public long getCount(long userId, LocalDate day) {
    Long count =
        jdbcTemplate.query(
            SELECT_COUNT_SQL,
            ps -> {
              ps.setLong(1, userId);
              ps.setObject(2, day);
            },
            rs -> rs.next() ? rs.getLong(1) : 0L);
    return count == null ? 0L : count;
  }
}
