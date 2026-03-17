package com.rft.chat_sribiddi.websearch;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {

  /** Maximum number of results to return from Brave Search (default: 5). */
  private int maxResults = 5;

  /** HTTP request timeout for Brave Search API calls (default: 10s). */
  private Duration requestTimeout = Duration.ofSeconds(10);
}
