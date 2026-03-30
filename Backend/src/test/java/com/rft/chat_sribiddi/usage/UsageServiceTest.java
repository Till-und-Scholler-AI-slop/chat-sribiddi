package com.rft.chat_sribiddi.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

  @Mock private UsageStatsRepository usageStatsRepository;

  private UsageProperties usageProperties;
  private UsageService usageService;

  @BeforeEach
  void setUp() {
    usageProperties = new UsageProperties();
    usageService = new UsageService(usageStatsRepository, usageProperties);
  }

  @Test
  void recordUsage_persistsEntity() {
    UUID chatId = UUID.randomUUID();

    usageService.recordUsage("user-1", chatId, "gpt-5.2", "OPENAI", 100L, 50L, 150L);

    ArgumentCaptor<UsageStatsEntity> captor = ArgumentCaptor.forClass(UsageStatsEntity.class);
    verify(usageStatsRepository).save(captor.capture());

    UsageStatsEntity saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo("user-1");
    assertThat(saved.getChatId()).isEqualTo(chatId);
    assertThat(saved.getModel()).isEqualTo("gpt-5.2");
    assertThat(saved.getProvider()).isEqualTo("OPENAI");
    assertThat(saved.getInputTokens()).isEqualTo(100L);
    assertThat(saved.getOutputTokens()).isEqualTo(50L);
    assertThat(saved.getTotalTokens()).isEqualTo(150L);
    assertThat(saved.getEstimatedCost()).isGreaterThan(0.0);
  }

  @Test
  void recordUsage_handlesNullTokens() {
    UUID chatId = UUID.randomUUID();

    usageService.recordUsage("user-1", chatId, "gpt-5.2", "OPENAI", null, null, null);

    ArgumentCaptor<UsageStatsEntity> captor = ArgumentCaptor.forClass(UsageStatsEntity.class);
    verify(usageStatsRepository).save(captor.capture());

    UsageStatsEntity saved = captor.getValue();
    assertThat(saved.getEstimatedCost()).isEqualTo(0.0);
  }

  @Test
  void recordUsage_swallowsException() {
    UUID chatId = UUID.randomUUID();
    when(usageStatsRepository.save(any())).thenThrow(new RuntimeException("DB error"));

    // Should not throw
    usageService.recordUsage("user-1", chatId, "gpt-5.2", "OPENAI", 100L, 50L, 150L);
  }

  @Test
  void getUsageStats_aggregatesByDay() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    Instant to = Instant.now();

    List<UsageAggregateRow> rows = List.of(
        new UsageAggregateRow("2026-03-29", 500L, 200L, 700L, 0.0011),
        new UsageAggregateRow("2026-03-30", 1000L, 400L, 1400L, 0.0022));

    when(usageStatsRepository.aggregateByDay("user-1", from, to)).thenReturn(rows);

    UsageResponseDTO result = usageService.getUsageStats("user-1", from, to, "day");

    assertThat(result.buckets()).hasSize(2);
    assertThat(result.buckets().getFirst().key()).isEqualTo("2026-03-29");
    assertThat(result.summary().totalTokens()).isEqualTo(2100L);
    assertThat(result.summary().totalEstimatedCost()).isGreaterThan(0.0);
  }

  @Test
  void getUsageStats_aggregatesByModel() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    Instant to = Instant.now();

    List<UsageAggregateRow> rows = List.of(
        new UsageAggregateRow("gpt-5.2", 5000L, 2000L, 7000L, 0.011));

    when(usageStatsRepository.aggregateByModel("user-1", from, to)).thenReturn(rows);

    UsageResponseDTO result = usageService.getUsageStats("user-1", from, to, "model");

    assertThat(result.buckets()).hasSize(1);
    assertThat(result.buckets().getFirst().key()).isEqualTo("gpt-5.2");
  }

  @Test
  void getUsageStats_aggregatesByProvider() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    Instant to = Instant.now();

    List<UsageAggregateRow> rows = List.of(
        new UsageAggregateRow("OPENAI", 5000L, 2000L, 7000L, 0.011));

    when(usageStatsRepository.aggregateByProvider("user-1", from, to)).thenReturn(rows);

    UsageResponseDTO result = usageService.getUsageStats("user-1", from, to, "provider");

    assertThat(result.buckets()).hasSize(1);
    assertThat(result.buckets().getFirst().key()).isEqualTo("OPENAI");
  }

  @Test
  void estimateCost_usesCustomPricing() {
    UsageProperties.ModelPrice price = new UsageProperties.ModelPrice();
    price.setInput(5.0);
    price.setOutput(15.0);
    usageProperties.getPrices().put("gpt-5.2", price);

    // 1000 input tokens at $5/1M = $0.005, 500 output tokens at $15/1M = $0.0075
    double cost = usageProperties.estimateCost("gpt-5.2", 1000L, 500L);
    assertThat(cost).isCloseTo(0.0125, org.assertj.core.data.Offset.offset(0.0001));
  }

  @Test
  void estimateCost_usesDefaultPricing_forUnknownModel() {
    // defaults: input=1.0, output=3.0 per 1M tokens
    double cost = usageProperties.estimateCost("unknown-model", 1_000_000L, 1_000_000L);
    assertThat(cost).isCloseTo(4.0, org.assertj.core.data.Offset.offset(0.0001));
  }
}
