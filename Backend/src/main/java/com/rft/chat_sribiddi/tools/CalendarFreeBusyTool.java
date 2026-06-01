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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class CalendarFreeBusyTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final String userId;
  private final ConnectorAccountService connectorAccountService;
  private final HttpClient httpClient;

  public CalendarFreeBusyTool(
      String userId, ConnectorAccountService connectorAccountService, HttpClient httpClient) {
    this.userId = userId;
    this.connectorAccountService = connectorAccountService;
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "calendar-freebusy";
  }

  @Override
  public String getDisplayName() {
    return "Calendar Availability";
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
    return ToolResult.success(freeBusy(stringValue(parameters, "timeMin"), stringValue(parameters, "timeMax")));
  }

  @Tool(description = "Check read-only free/busy availability from the user's primary Google Calendar.")
  public String freeBusy(
      @ToolParam(description = "ISO instant lower bound, defaults to now") String timeMin,
      @ToolParam(description = "ISO instant upper bound") String timeMax) {
    String token = connectorAccountService.accessToken(userId, ConnectorProvider.GOOGLE_CALENDAR);
    String start = hasText(timeMin) ? timeMin : Instant.now().toString();
    String end = hasText(timeMax) ? timeMax : Instant.now().plusSeconds(7 * 24 * 60 * 60).toString();
    String body = json(Map.of("timeMin", start, "timeMax", end, "items", List.of(Map.of("id", "primary"))));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://www.googleapis.com/calendar/v3/freeBusy"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode root = MAPPER.readTree(response.body());
      return json(
          Map.of(
              "type", "calendar",
              "mode", "freebusy",
              "timeMin", start,
              "timeMax", end,
              "busy", root.path("calendars").path("primary").path("busy")));
    } catch (IOException e) {
      return json(Map.of("type", "calendar", "errorCode", "CALENDAR_REQUEST_FAILED", "message", e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return json(Map.of("type", "calendar", "errorCode", "CALENDAR_INTERRUPTED"));
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

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
