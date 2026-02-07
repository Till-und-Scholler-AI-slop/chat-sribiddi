package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.service.ChatService.ClientHolder;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface ProviderClientFactory {
  /** Returns a cached client holder for the given provider+apiKey combo. */
  ClientHolder getClient(Provider provider, String apiKey);

  /** Builds provider-specific chat options including per-request API key/header. */
  ChatOptions buildOptions(Provider provider, String modelId, String apiKey);
}
