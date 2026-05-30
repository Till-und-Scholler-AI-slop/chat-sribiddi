package com.rft.chat_sribiddi.tools;

import java.time.Duration;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.tools")
public class ToolProperties {
  private Set<String> defaultEnabled = Set.of("calculator", "current-datetime", "brave-search");
  private int urlFetcherMaxContentLength = 50_000;
  private Duration urlFetcherTimeout = Duration.ofSeconds(10);
  private long calculatorEvalTimeoutMs = 3000;
}
