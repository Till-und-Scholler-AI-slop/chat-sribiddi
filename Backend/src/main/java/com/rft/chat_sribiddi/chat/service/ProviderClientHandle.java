package com.rft.chat_sribiddi.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public record ProviderClientHandle(ChatClient client, AutoCloseable closeable)
    implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ProviderClientHandle.class);

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
