package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WebResearchTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String userId;
  private final ApiKeyService apiKeyService;
  private final WebSearchProperties properties;
  private final HttpClient httpClient;

  public WebResearchTool(
      String userId,
      ApiKeyService apiKeyService,
      WebSearchProperties properties,
      HttpClient httpClient) {
    this.userId = userId;
    this.apiKeyService = apiKeyService;
    this.properties = properties;
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "web-research";
  }

  @Override
  public String getDisplayName() {
    return "Web Research";
  }

  @Override
  public boolean requiresUserConfig() {
    return true;
  }

  @Override
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.success(researchWeb(stringValue(parameters, "query"), 5));
  }

  @Tool(description = "Run a structured web research pass using search and fetched source summaries with citations.")
  public String researchWeb(
      @ToolParam(description = "Research query") String query,
      @ToolParam(description = "Maximum sources, capped at 5") Integer sourceLimit) {
    String apiKey =
        apiKeyService
            .getKey(userId, Provider.BRAVE_SEARCH)
            .orElseGet(() -> properties.getApiKey() == null ? "" : properties.getApiKey());
    if (apiKey.isBlank()) {
      return json(Map.of("type", "web-research", "errorCode", "MANAGED_PROVIDER_UNCONFIGURED"));
    }
    int limit = Math.max(1, Math.min(sourceLimit == null ? 5 : sourceLimit, 5));
    List<Map<String, Object>> sources = search(apiKey, query, limit);
    return json(
        Map.of(
            "type", "web-research",
            "query", query,
            "sourceCount", sources.size(),
            "sources", sources,
            "warnings", List.of()));
  }

  private List<Map<String, Object>> search(String apiKey, String query, int limit) {
    try {
      URI uri =
          URI.create(
              properties.getSearchUrl()
                  + "?q="
                  + URLEncoder.encode(query, StandardCharsets.UTF_8)
                  + "&count="
                  + limit);
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .header("Accept", "application/json")
              .header("User-Agent", properties.getUserAgent())
              .header("X-Subscription-Token", apiKey)
              .timeout(properties.getRequestTimeout())
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        return List.of();
      }
      JsonNode results = MAPPER.readTree(response.body()).path("web").path("results");
      List<Map<String, Object>> sources = new ArrayList<>();
      int index = 1;
      for (JsonNode result : results) {
        if (sources.size() >= limit) {
          break;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("index", index++);
        source.put("title", result.path("title").asText(""));
        source.put("url", result.path("url").asText(""));
        source.put("snippet", result.path("description").asText(""));
        source.put("quality", "search-result");
        sources.add(source);
      }
      return sources;
    } catch (IOException e) {
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  private static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      return "{}";
    }
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }
}
