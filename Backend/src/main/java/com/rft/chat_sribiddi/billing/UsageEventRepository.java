package com.rft.chat_sribiddi.billing;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEventEntity, Long> {

  /**
   * Aggregates usage events for the given user since {@code since}, grouped by provider and
   * model. Used by {@code GET /billing/usage} to render a per-model summary for the trailing
   * 30 days.
   *
   * <p>{@code totalTokens} falls back per-row to {@code promptTokens + completionTokens} when
   * the provider only reported the split (some providers omit the total in their usage payload
   * — see {@link UsageEventService#record}). Null columns are coalesced to zero so SUM() always
   * returns a numeric value.
   */
  @Query(
      """
      SELECT u.provider AS provider,
             u.modelId AS modelId,
             COUNT(u) AS calls,
             COALESCE(SUM(u.promptTokens), 0) AS promptTokens,
             COALESCE(SUM(u.completionTokens), 0) AS completionTokens,
             COALESCE(SUM(COALESCE(u.totalTokens,
                 COALESCE(u.promptTokens, 0) + COALESCE(u.completionTokens, 0))), 0) AS totalTokens,
             COALESCE(SUM(u.costMicroCents), 0) AS costMicroCents
      FROM UsageEventEntity u
      WHERE u.userId = :userId
        AND u.createdAt >= :since
      GROUP BY u.provider, u.modelId
      ORDER BY calls DESC
      """)
  List<UsageAggregate> aggregateSince(@Param("userId") Long userId, @Param("since") Instant since);

  /** Projection returned by {@link #aggregateSince(Long, Instant)}. */
  interface UsageAggregate {
    com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider getProvider();

    String getModelId();

    long getCalls();

    long getPromptTokens();

    long getCompletionTokens();

    long getTotalTokens();

    long getCostMicroCents();
  }
}
