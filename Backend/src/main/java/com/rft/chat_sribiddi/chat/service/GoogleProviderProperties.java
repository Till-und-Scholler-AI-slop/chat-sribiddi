package com.rft.chat_sribiddi.chat.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.google")
public class GoogleProviderProperties {

  // Gemma models can have longer time-to-first-token than Gemini; keep this comfortably above that.
  private Duration timeout = Duration.ofSeconds(120);

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    if (timeout == null) {
      throw new IllegalArgumentException("Google timeout must not be null");
    }
    long timeoutMillis = timeout.toMillis();
    if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "Google timeout must be between 1ms and %dms".formatted(Integer.MAX_VALUE));
    }
    this.timeout = timeout;
  }
}
