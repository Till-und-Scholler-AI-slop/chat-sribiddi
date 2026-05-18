package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.auth.Plan;
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
  private int freeRequestsPerMinute = 600;
  private int premiumRequestsPerMinute = 1200;
  private int maxRequestsPerMinute = 2400;
  private Duration bucketTtl = Duration.ofMinutes(10);
  private long redisFailureRetryAfterSeconds = 60;
  private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

  public int limitFor(Plan plan) {
    return switch (plan == null ? Plan.FREE : plan) {
      case FREE -> freeRequestsPerMinute;
      case PREMIUM -> premiumRequestsPerMinute;
      case MAX -> maxRequestsPerMinute;
    };
  }
}
