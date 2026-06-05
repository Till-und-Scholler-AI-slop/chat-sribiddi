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

  /** User-Agent sent to outbound search APIs. */
  private String userAgent = "ChatSribiddi/1.0 (WebSearch)";

  /** Deprecated server-side Brave Search key; production should use user provider connections. */
  private String apiKey = "";

  private String searchUrl = "https://api.search.brave.com/res/v1/web/search";
}
