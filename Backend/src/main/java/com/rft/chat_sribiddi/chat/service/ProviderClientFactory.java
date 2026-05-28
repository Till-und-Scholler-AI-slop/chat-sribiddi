package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.ChatRuntimeOptions;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface ProviderClientFactory {
  /**
   * Returns a cached client handle for the given provider+apiKey combo, defaulting to
   * {@link BillingMode#BYOK} cache partitioning to preserve the existing BYOK path behaviour.
   */
  default ProviderClientHandle getClient(Provider provider, String apiKey) {
    return getClient(provider, apiKey, BillingMode.BYOK);
  }

  /**
   * Returns a cached client handle for the given provider+apiKey+billingMode triple. The
   * {@link BillingMode} is part of the cache key so that user-supplied (BYOK) keys and
   * server-managed (MANAGED) keys never share cache entries, even if their raw string values were
   * to collide.
   */
  ProviderClientHandle getClient(Provider provider, String apiKey, BillingMode billingMode);

  /** Builds provider-specific chat options including per-request API key/header. */
  ChatOptions buildOptions(Provider provider, String modelId, String apiKey);

  /**
   * Builds provider-specific chat options including request-scoped Spring AI runtime options.
   * Implementations that do not support request overrides can delegate to the legacy overload.
   */
  default ChatOptions buildOptions(
      Provider provider, String modelId, String apiKey, ChatRuntimeOptions runtimeOptions) {
    return buildOptions(provider, modelId, apiKey);
  }
}
