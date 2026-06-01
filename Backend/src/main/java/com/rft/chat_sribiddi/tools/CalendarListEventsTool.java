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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class CalendarListEventsTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final String userId;
  private final ConnectorAccountService connectorAccountService;
  private final HttpClient httpClient;

  public CalendarListEventsTool(
      String userId, ConnectorAccountService connectorAccountService, HttpClient httpClient) {
    this.userId = userId;
    this.connectorAccountService = connectorAccountService;
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "calendar-list-events";
  }

  @Override
  public String getDisplayName() {
    return "Calendar Events";
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
    return ToolResult.success(
        listEvents(stringValue(parameters, "timeMin"), stringValue(parameters, "timeMax"), 10));
  }

  @Tool(description = "List upcoming events from the user's connected Google Calendar read-only.")
  public String listEvents(
      @ToolParam(description = "ISO instant lower bound, defaults to now") String timeMin,
      @ToolParam(description = "ISO instant upper bound, optional") String timeMax,
      @ToolParam(description = "Maximum events, capped at 20") Integer limit) {
    String token = connectorAccountService.accessToken(userId, ConnectorProvider.GOOGLE_CALENDAR);
    StringBuilder url =
        new StringBuilder("https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime");
    url.append("&timeMin=").append(encode(hasText(timeMin) ? timeMin : Instant.now().toString()));
    if (hasText(timeMax)) {
      url.append("&timeMax=").append(encode(timeMax));
    }
    url.append("&maxResults=").append(Math.max(1, Math.min(limit == null ? 10 : limit, 20)));
    JsonNode root = get(token, url.toString());
    var events = new ArrayList<Map<String, Object>>();
    for (JsonNode item : root.path("items")) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("summary", item.path("summary").asText(""));
      event.put("start", first(item.path("start").path("dateTime"), item.path("start").path("date")));
      event.put("end", first(item.path("end").path("dateTime"), item.path("end").path("date")));
      event.put("status", item.path("status").asText(""));
      events.add(event);
    }
    return json(Map.of("type", "calendar", "mode", "events", "eventCount", events.size(), "events", events));
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
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      return MAPPER.createObjectNode().put("error", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return MAPPER.createObjectNode().put("error", "interrupted");
    }
  }

  private static String first(JsonNode first, JsonNode second) {
    String value = first.asText(null);
    return hasText(value) ? value : second.asText("");
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

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
