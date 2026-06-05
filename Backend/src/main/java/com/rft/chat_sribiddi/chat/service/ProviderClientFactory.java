package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.ChatRuntimeOptions;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface ProviderClientFactory {
  /**
   * Returns a cached client handle for the given provider+apiKey combo, defaulting to user-key
   * cache partitioning.
   */
  default ProviderClientHandle getClient(Provider provider, String apiKey) {
    return getClient(provider, apiKey, CredentialSource.USER_KEY);
  }

  /**
   * Returns a cached client handle for the given provider+apiKey+credential source triple.
   */
  ProviderClientHandle getClient(Provider provider, String apiKey, CredentialSource credentialSource);

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
