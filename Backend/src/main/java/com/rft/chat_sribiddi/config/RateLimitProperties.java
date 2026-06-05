package com.rft.chat_sribiddi.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

  public enum Backend {
    MEMORY,
    REDIS
  }

  private boolean enabled = true;
  private Backend backend = Backend.REDIS;
  private int anonymousRequestsPerMinute = 300;
  private int authenticatedRequestsPerMinute = 600;
  private Duration bucketTtl = Duration.ofMinutes(10);
  private long redisFailureRetryAfterSeconds = 60;
  private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));
}
