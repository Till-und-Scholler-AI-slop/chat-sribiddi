package com.rft.chat_sribiddi.chat.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

  private int contextMessageLimit = 20;

  private int maxMediaAttachments = 5;

  private long maxMediaAttachmentBytes = 5L * 1024 * 1024;

  private long maxMediaTotalBytes = 20L * 1024 * 1024;

  /** SSE stream timeout in milliseconds (default: 3 minutes). */
  private long streamTimeoutMs = 180_000L;
}
