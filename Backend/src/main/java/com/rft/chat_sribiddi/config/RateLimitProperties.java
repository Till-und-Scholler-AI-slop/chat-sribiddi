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

  private boolean enabled = true;
  private int anonymousRequestsPerMinute = 10;
  private int freeRequestsPerMinute = 30;
  private int premiumRequestsPerMinute = 120;
  private int maxRequestsPerMinute = 300;
  private Duration bucketTtl = Duration.ofMinutes(10);
  private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

  public int limitFor(Plan plan) {
    return switch (plan == null ? Plan.FREE : plan) {
      case FREE -> freeRequestsPerMinute;
      case PREMIUM -> premiumRequestsPerMinute;
      case MAX -> maxRequestsPerMinute;
    };
  }
}
