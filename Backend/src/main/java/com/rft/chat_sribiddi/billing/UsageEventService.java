package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records per-call usage/cost events. This is the BILL-5 integration hook invoked by
 * {@link com.rft.chat_sribiddi.chat.service.ChatService} after every provider call (including
 * stream completion) so that managed-mode billing and reporting can consume the data.
 *
 * <p>The current implementation logs at {@code debug} level only — persistent storage in the
 * {@code usage_events} table (created by Flyway {@code V7}) is the subject of BILL-6.
 */
@Slf4j
@Service
public class UsageEventService {

  /**
   * Records a usage event for a single provider call.
   *
   * @param userId the OAuth provider id of the caller (matches {@code User.providerId})
   * @param provider the AI provider the call was routed to
   * @param modelId the model id requested
   * @param promptTokens prompt tokens reported by the provider (may be {@code null})
   * @param generationTokens completion/generation tokens reported by the provider (may be
   *     {@code null})
   * @param totalTokens total tokens reported by the provider (may be {@code null})
   * @param messageId id of the persisted assistant {@code MessageEntity}, or {@code null} when no
   *     assistant message was saved (e.g. stream error before persistence)
   * @param billingMode the effective billing mode for the call
   */
  public void record(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long generationTokens,
      Long totalTokens,
      Long messageId,
      BillingMode billingMode) {
    log.debug(
        "usage_event user={} provider={} model={} prompt={} generation={} total={} messageId={} mode={}",
        userId,
        provider,
        modelId,
        promptTokens,
        generationTokens,
        totalTokens,
        messageId,
        billingMode);
  }
}
