package com.rft.chat_sribiddi.usage;

import java.util.List;

/**
 * Response DTO for usage statistics.
 */
public record UsageResponseDTO(
    List<UsageBucket> buckets,
    UsageSummary summary) {

  public record UsageBucket(
      String key,
      long inputTokens,
      long outputTokens,
      long totalTokens,
      double estimatedCost) {}

  public record UsageSummary(
      long totalInputTokens,
      long totalOutputTokens,
      long totalTokens,
      double totalEstimatedCost) {}
}
