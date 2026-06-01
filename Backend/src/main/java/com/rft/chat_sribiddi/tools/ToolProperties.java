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
  private int urlFetcherMaxHtmlSize = 2_000_000;
  private Duration urlFetcherTimeout = Duration.ofSeconds(10);
  private long urlFetcherConversionTimeoutMs = 5_000;
  private String urlFetcherDefaultFormat = "markdown";
  private String urlFetcherUserAgent = "ChatSribiddi/1.0 (Tool)";
  private int urlFetcherRateLimitPerMinute = 10;
  private int urlFetcherMaxRedirects = 5;
  private long calculatorEvalTimeoutMs = 3000;
}
