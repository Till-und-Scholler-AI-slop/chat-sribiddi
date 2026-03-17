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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tool that performs web searches via the Brave Search API.
 *
 * <p>Instances are created per-request with the user's API key and passed to the ChatClient via
 * {@code .tools(new BraveSearchTool(apiKey))}.
 */
public class BraveSearchTool {

  private static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
  private static final int MAX_RESULTS = 5;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String apiKey;

  public BraveSearchTool(String apiKey) {
    this.apiKey = apiKey;
  }

  @Tool(description = "Search the web for current information using Brave Search. "
      + "Use this tool when the user asks about recent events, real-time data, "
      + "or any topic that may require up-to-date information from the internet.")
  public String searchWeb(
      @ToolParam(description = "The search query to look up on the web") String query) {
    try {
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
      URI uri =
          URI.create(BRAVE_SEARCH_URL + "?q=" + encodedQuery + "&count=" + MAX_RESULTS);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(uri)
              .header("Accept", "application/json")
              .header("X-Subscription-Token", apiKey)
              .timeout(REQUEST_TIMEOUT)
              .GET()
              .build();

      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        return "Web search failed (HTTP %d). Please try a different query."
            .formatted(response.statusCode());
      }

      return parseResults(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Web search was interrupted.";
    } catch (Exception e) {
      return "Web search failed: " + e.getMessage();
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
        if (count >= MAX_RESULTS) break;
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
      return "Failed to parse search results.";
    }
  }
}
