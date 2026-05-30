package com.rft.chat_sribiddi.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tool that performs web searches via the Brave Search API.
 *
 * <p>Instances are created per-request with the user's API key, adapted to Spring AI
 * {@code ToolCallback}s, and passed to the ChatClient for that single prompt.
 */
@Slf4j
public class BraveSearchTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String apiKey;
  private final HttpClient httpClient;
  private final WebSearchProperties properties;
  private final int maxResults;
  private final Duration requestTimeout;
  private final String userAgent;

  public BraveSearchTool(String apiKey, HttpClient httpClient, WebSearchProperties properties) {
    this.apiKey = apiKey;
    this.httpClient = httpClient;
    this.properties = properties;
    this.maxResults = properties.getMaxResults();
    this.requestTimeout = properties.getRequestTimeout();
    this.userAgent = properties.getUserAgent();
  }

  @Tool(description = "Search the web for current information using Brave Search. "
      + "Use this tool when the user asks about recent events, real-time data, "
      + "or any topic that may require up-to-date information from the internet.")
  public String searchWeb(
      @ToolParam(description = "The search query to look up on the web") String query) {
    try {
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
      URI uri =
          URI.create(properties.getSearchUrl() + "?q=" + encodedQuery + "&count=" + maxResults);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(uri)
              .header("Accept", "application/json")
              .header("User-Agent", userAgent)
              .header("X-Subscription-Token", apiKey)
              .timeout(requestTimeout)
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Brave Search API returned HTTP {}", response.statusCode());
        return "Web search failed (HTTP %d). Please try a different query."
            .formatted(response.statusCode());
      }

      return parseResults(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Web search was interrupted.";
    } catch (Exception e) {
      log.warn("Web search request failed", e);
      return "Web search is currently unavailable. Please try again later.";
    }
  }

  private String parseResults(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      JsonNode webResults = root.path("web").path("results");

      if (webResults.isMissingNode() || !webResults.isArray() || webResults.isEmpty()) {
        return "No web results found for this query.";
      }

      StringBuilder sb = new StringBuilder("Web search results:\n\n");
      int count = 0;
      for (JsonNode result : webResults) {
        if (count >= maxResults) break;
        count++;

        String title = result.path("title").asText("");
        String url = result.path("url").asText("");
        String description = result.path("description").asText("");

        sb.append("%d. **%s**\n".formatted(count, title));
        sb.append("   URL: %s\n".formatted(url));
        if (!description.isEmpty()) {
          sb.append("   %s\n".formatted(description));
        }
        sb.append("\n");
      }

      return sb.toString().trim();
    } catch (Exception e) {
      log.warn("Failed to parse Brave Search response", e);
      return "Failed to parse search results.";
    }
  }
}
