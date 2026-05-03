package com.rft.chat_sribiddi.config;

import java.time.Duration;
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
  private int anonymousRequestsPerMinute = 60;
  private int authenticatedRequestsPerMinute = 300;
  private Duration bucketTtl = Duration.ofMinutes(10);
}
