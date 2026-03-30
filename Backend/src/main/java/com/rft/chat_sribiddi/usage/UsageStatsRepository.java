package com.rft.chat_sribiddi.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UsageStatsRepository extends JpaRepository<UsageStatsEntity, Long> {

  List<UsageStatsEntity> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
      String userId, Instant from, Instant to);

  @Query(
      """
      SELECT new com.rft.chat_sribiddi.usage.UsageAggregateRow(
        CAST(CAST(u.createdAt AS date) AS string),
        COALESCE(SUM(u.inputTokens), 0),
        COALESCE(SUM(u.outputTokens), 0),
        COALESCE(SUM(u.totalTokens), 0),
        COALESCE(SUM(u.estimatedCost), 0.0)
      )
      FROM UsageStatsEntity u
      WHERE u.userId = :userId AND u.createdAt BETWEEN :from AND :to
      GROUP BY CAST(u.createdAt AS date)
      ORDER BY CAST(u.createdAt AS date)
      """)
  List<UsageAggregateRow> aggregateByDay(
      @Param("userId") String userId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  @Query(
      """
      SELECT new com.rft.chat_sribiddi.usage.UsageAggregateRow(
        u.model,
        COALESCE(SUM(u.inputTokens), 0),
        COALESCE(SUM(u.outputTokens), 0),
        COALESCE(SUM(u.totalTokens), 0),
        COALESCE(SUM(u.estimatedCost), 0.0)
      )
      FROM UsageStatsEntity u
      WHERE u.userId = :userId AND u.createdAt BETWEEN :from AND :to
      GROUP BY u.model
      ORDER BY COALESCE(SUM(u.totalTokens), 0) DESC
      """)
  List<UsageAggregateRow> aggregateByModel(
      @Param("userId") String userId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  @Query(
      """
      SELECT new com.rft.chat_sribiddi.usage.UsageAggregateRow(
        u.provider,
        COALESCE(SUM(u.inputTokens), 0),
        COALESCE(SUM(u.outputTokens), 0),
        COALESCE(SUM(u.totalTokens), 0),
        COALESCE(SUM(u.estimatedCost), 0.0)
      )
      FROM UsageStatsEntity u
      WHERE u.userId = :userId AND u.createdAt BETWEEN :from AND :to
      GROUP BY u.provider
      ORDER BY COALESCE(SUM(u.totalTokens), 0) DESC
      """)
  List<UsageAggregateRow> aggregateByProvider(
      @Param("userId") String userId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  @Query(
      """
      SELECT new com.rft.chat_sribiddi.usage.UsageAggregateRow(
        CAST(u.chatId AS string),
        COALESCE(SUM(u.inputTokens), 0),
        COALESCE(SUM(u.outputTokens), 0),
        COALESCE(SUM(u.totalTokens), 0),
        COALESCE(SUM(u.estimatedCost), 0.0)
      )
      FROM UsageStatsEntity u
      WHERE u.userId = :userId AND u.createdAt BETWEEN :from AND :to
      GROUP BY u.chatId
      ORDER BY COALESCE(SUM(u.totalTokens), 0) DESC
      """)
  List<UsageAggregateRow> aggregateByChatId(
      @Param("userId") String userId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  void deleteByUserId(String userId);
}
