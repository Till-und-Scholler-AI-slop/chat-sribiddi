package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.service.ChatService.ClientHolder;

public interface ProviderClientFactory {
  ClientHolder create(Provider provider, String apiKey);
}
