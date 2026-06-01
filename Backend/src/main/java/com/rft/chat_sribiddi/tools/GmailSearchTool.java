package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.connectors.ConnectorAccountService;
import com.rft.chat_sribiddi.connectors.ConnectorProvider;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
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

public class GmailSearchTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String userId;
  private final ConnectorAccountService connectorAccountService;
  private final HttpClient httpClient;

  public GmailSearchTool(
      String userId, ConnectorAccountService connectorAccountService, HttpClient httpClient) {
    this.userId = userId;
    this.connectorAccountService = connectorAccountService;
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "gmail-search";
  }

  @Override
  public String getDisplayName() {
    return "Gmail Search";
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
    return ToolResult.success(searchGmail(stringValue(parameters, "query"), 5));
  }

  @Tool(description = "Search the user's connected Gmail account read-only and return matching message metadata and snippets.")
  public String searchGmail(
      @ToolParam(description = "Gmail search query, e.g. from:person@example.com newer_than:30d") String query,
      @ToolParam(description = "Maximum number of messages to return, capped at 10") Integer limit) {
    if (query == null || query.isBlank()) {
      return json(Map.of("type", "gmail", "errorCode", "MISSING_QUERY"));
    }
    String token = connectorAccountService.accessToken(userId, ConnectorProvider.GOOGLE_GMAIL);
    int max = Math.max(1, Math.min(limit == null ? 5 : limit, 10));
    JsonNode search =
        get(
            token,
            "https://gmail.googleapis.com/gmail/v1/users/me/messages?q="
                + encode(query)
                + "&maxResults="
                + max);
    List<Map<String, Object>> messages = new ArrayList<>();
    for (JsonNode item : search.path("messages")) {
      String id = item.path("id").asText(null);
      if (id == null) {
        continue;
      }
      JsonNode message =
          get(
              token,
              "https://gmail.googleapis.com/gmail/v1/users/me/messages/"
                  + encode(id)
                  + "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date");
      messages.add(messageSummary(message));
    }
    return json(
        Map.of(
            "type", "gmail",
            "query", query,
            "resultCount", messages.size(),
            "messages", messages));
  }

  private JsonNode get(String token, String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        return MAPPER.createObjectNode().put("error", "Gmail request failed");
      }
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      return MAPPER.createObjectNode().put("error", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return MAPPER.createObjectNode().put("error", "interrupted");
    }
  }

  private Map<String, Object> messageSummary(JsonNode message) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", message.path("id").asText(""));
    result.put("threadId", message.path("threadId").asText(""));
    result.put("snippet", message.path("snippet").asText(""));
    Map<String, String> headers = new LinkedHashMap<>();
    for (JsonNode header : message.path("payload").path("headers")) {
      headers.put(header.path("name").asText(""), header.path("value").asText(""));
    }
    result.put("subject", headers.getOrDefault("Subject", ""));
    result.put("from", headers.getOrDefault("From", ""));
    result.put("date", headers.getOrDefault("Date", ""));
    return result;
  }

  private static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      return "{}";
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }
}
