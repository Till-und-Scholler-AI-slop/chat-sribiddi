package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.UsageEventService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUsageRecorder {

  private final UsageEventService usageEventService;
  private final ChatMetrics chatMetrics;

  TokenUsage tokenUsage(Usage usage) {
    if (usage == null) {
      return TokenUsage.EMPTY;
    }
    return new TokenUsage(
        safeLong(usage.getPromptTokens()),
        safeLong(usage.getCompletionTokens()),
        safeLong(usage.getTotalTokens()));
  }

  void recordRequestMetric(Provider provider, String modelId, boolean providerCallSucceeded) {
    chatMetrics.recordRequest(provider, modelId, providerCallSucceeded ? "success" : "error");
  }

  void recordUsage(ChatUsage usage) {
    recordRequestMetric(usage.provider(), usage.modelId(), usage.providerCallSucceeded());
    chatMetrics.recordTokens(usage.provider(), usage.modelId(), "prompt", usage.tokenUsage().promptTokens());
    chatMetrics.recordTokens(
        usage.provider(), usage.modelId(), "completion", usage.tokenUsage().generationTokens());
    try {
      usageEventService.record(
          usage.userId(),
          usage.provider(),
          usage.modelId(),
          usage.tokenUsage().promptTokens(),
          usage.tokenUsage().generationTokens(),
          usage.tokenUsage().totalTokens(),
          usage.messageId(),
          usage.credentialSource());
    } catch (Exception ex) {
      log.warn("Failed to record usage event for user {}", usage.userId(), ex);
      chatMetrics.recordRequest(usage.provider(), usage.modelId(), "usage_record_failed");
    }
  }

  private static Long safeLong(Number value) {
    return value == null ? null : value.longValue();
  }

  record ChatUsage(
      String userId,
      Provider provider,
      String modelId,
      TokenUsage tokenUsage,
      Long messageId,
      CredentialSource credentialSource,
      boolean providerCallSucceeded) {}

  record TokenUsage(Long promptTokens, Long generationTokens, Long totalTokens) {
    private static final TokenUsage EMPTY = new TokenUsage(null, null, null);
  }
}
