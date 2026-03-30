package com.rft.chat_sribiddi.usage;

/**
 * Projection row returned by JPQL aggregate queries.
 */
public record UsageAggregateRow(
    String groupKey,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    double estimatedCost) {}
