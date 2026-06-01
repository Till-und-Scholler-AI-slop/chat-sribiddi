package com.rft.chat_sribiddi.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

@Slf4j
public record ProviderClientHandle(ChatClient client, AutoCloseable closeable)
    implements AutoCloseable {

  @Override
  public void close() {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception e) {
      log.warn("Failed to close provider client", e);
    }
  }
}
