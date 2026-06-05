package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records per-call usage/cost events. This is the BILL-5 integration hook invoked by
 * {@link com.rft.chat_sribiddi.chat.service.ChatService} after every provider call (including
 * stream completion) so that usage and cost analytics can consume the data.
 *
 * <p>Rows are persisted to the {@code usage_events} table created by Flyway {@code V7}. Cost is
 * estimated from the model pricing catalog when pricing is known.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageEventService {

  private static final BigDecimal MICRO_CENTS_PER_USD = BigDecimal.valueOf(100_000_000L);
  private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);

  private final UserRepository userRepository;
  private final UsageEventRepository usageEventRepository;
  private final ModelPricingLookup modelPricingLookup;

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
   * @param credentialSource the credential source used for the call
   */
  @Transactional
  public void record(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long generationTokens,
      Long totalTokens,
      Long messageId,
      CredentialSource credentialSource) {
    log.debug(
        "usage_event user={} provider={} model={} prompt={} generation={} total={} messageId={} source={}",
        userId,
        provider,
        modelId,
        promptTokens,
        generationTokens,
        totalTokens,
        messageId,
        credentialSource);
    persist(
        userId,
        provider,
        modelId,
        promptTokens,
        generationTokens,
        totalTokens,
        messageId,
        credentialSource);
  }

  protected void persist(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long generationTokens,
      Long totalTokens,
      Long messageId,
      CredentialSource credentialSource) {
    if (provider == null || modelId == null || modelId.isBlank()) {
      return;
    }
    User user = userRepository.findByProviderId(userId).orElse(null);
    if (user == null || user.getId() == null) {
      log.debug("Skipping usage event persistence: user {} is not persisted", userId);
      return;
    }

    UsageEventEntity entity = new UsageEventEntity();
    entity.setUserId(user.getId());
    entity.setMessageId(messageId);
    entity.setProvider(provider);
    entity.setModelId(modelId);
    entity.setPromptTokens(promptTokens);
    entity.setCompletionTokens(generationTokens);
    entity.setTotalTokens(totalTokens);
    entity.setCostMicroCents(
        estimateCostMicroCents(provider, modelId, promptTokens, generationTokens));
    entity.setCredentialSource(credentialSource);
    usageEventRepository.save(entity);
  }

  private Long estimateCostMicroCents(
      Provider provider, String modelId, Long promptTokens, Long generationTokens) {
    return modelPricingLookup
        .find(provider, modelId)
        .map(
            pricing -> {
              BigDecimal promptCost =
                  tokenCost(promptTokens, pricing.inputUsdPerMillion());
              BigDecimal completionCost =
                  tokenCost(generationTokens, pricing.outputUsdPerMillion());
              return promptCost
                  .add(completionCost)
                  .multiply(MICRO_CENTS_PER_USD)
                  .setScale(0, RoundingMode.HALF_UP)
                  .longValue();
            })
        .orElse(null);
  }

  private static BigDecimal tokenCost(Long tokens, double usdPerMillion) {
    if (tokens == null || tokens <= 0 || usdPerMillion <= 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(tokens)
        .multiply(BigDecimal.valueOf(usdPerMillion))
        .divide(TOKENS_PER_MILLION, 12, RoundingMode.HALF_UP);
  }
}
