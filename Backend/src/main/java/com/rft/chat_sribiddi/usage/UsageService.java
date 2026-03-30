package com.rft.chat_sribiddi.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

  private final UsageStatsRepository usageStatsRepository;
  private final UsageProperties usageProperties;

  /**
   * Persists a usage record. Called asynchronously so it never blocks the chat response path.
   */
  @Async
  public void recordUsage(
      String userId, UUID chatId, String model, String provider,
      Long inputTokens, Long outputTokens, Long totalTokens) {
    try {
      double cost = usageProperties.estimateCost(model, inputTokens, outputTokens);

      UsageStatsEntity entity = new UsageStatsEntity();
      entity.setUserId(userId);
      entity.setChatId(chatId);
      entity.setModel(model);
      entity.setProvider(provider);
      entity.setInputTokens(inputTokens);
      entity.setOutputTokens(outputTokens);
      entity.setTotalTokens(totalTokens);
      entity.setEstimatedCost(cost);

      usageStatsRepository.save(entity);
      log.debug("Recorded usage for user={}, model={}, tokens={}", userId, model, totalTokens);
    } catch (Exception e) {
      log.warn("Failed to record usage stats for user={}, chat={}: {}", userId, chatId, e.getMessage());
    }
  }

  public UsageResponseDTO getUsageStats(String userId, Instant from, Instant to, String groupBy) {
    List<UsageAggregateRow> rows = switch (groupBy) {
      case "model" -> usageStatsRepository.aggregateByModel(userId, from, to);
      case "provider" -> usageStatsRepository.aggregateByProvider(userId, from, to);
      case "chat" -> usageStatsRepository.aggregateByChatId(userId, from, to);
      default -> usageStatsRepository.aggregateByDay(userId, from, to);
    };

    List<UsageResponseDTO.UsageBucket> buckets = rows.stream()
        .map(r -> new UsageResponseDTO.UsageBucket(
            r.groupKey(), r.inputTokens(), r.outputTokens(), r.totalTokens(), r.estimatedCost()))
        .toList();

    long totalIn = rows.stream().mapToLong(UsageAggregateRow::inputTokens).sum();
    long totalOut = rows.stream().mapToLong(UsageAggregateRow::outputTokens).sum();
    long totalAll = rows.stream().mapToLong(UsageAggregateRow::totalTokens).sum();
    double totalCost = rows.stream().mapToDouble(UsageAggregateRow::estimatedCost).sum();

    return new UsageResponseDTO(
        buckets,
        new UsageResponseDTO.UsageSummary(totalIn, totalOut, totalAll, totalCost));
  }

  /**
   * Returns raw usage rows for CSV export.
   */
  public List<UsageStatsEntity> getRawUsage(String userId, Instant from, Instant to) {
    return usageStatsRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId, from, to);
  }
}
