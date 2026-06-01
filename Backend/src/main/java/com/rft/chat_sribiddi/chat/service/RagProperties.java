package com.rft.chat_sribiddi.chat.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.chat.rag")
public class RagProperties {
  private int maxChunks = 4;
  private int targetTokens = 1_200;
  private int charsPerToken = 4;
  private int maxContextChars = 12_000;
}
