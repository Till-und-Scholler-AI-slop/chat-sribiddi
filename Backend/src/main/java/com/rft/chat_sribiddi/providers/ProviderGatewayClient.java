package com.rft.chat_sribiddi.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
public class ProviderGatewayClient {

  private final ProviderGatewayProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ProviderGatewayClient(ProviderGatewayProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public void streamChat(GatewayChatRequest request, Consumer<GatewayStreamEvent> eventConsumer) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
    if (!properties.isEnabled()) {
      throw new ProviderGatewayException("Provider gateway is disabled");
    }
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(streamUri())
              .timeout(Duration.ofMinutes(5))
              .header("content-type", "application/json")
              .header("accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
              .build();
      HttpResponse<Stream<String>> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
      try (Stream<String> lines = response.body()) {
        if (response.statusCode() >= 400) {
          throw new ProviderGatewayException(
              "Provider gateway request failed with status %d: %s"
                  .formatted(response.statusCode(), String.join("\n", lines.toList())));
        }
        parseSse(lines, eventConsumer);
      }
    } catch (IOException e) {
      throw new ProviderGatewayException("Provider gateway request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderGatewayException("Provider gateway request was interrupted", e);
    }
  }

  private URI streamUri() {
    String baseUrl = properties.getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ProviderGatewayException("Provider gateway baseUrl is not configured");
    }
    return URI.create(baseUrl.trim()).resolve("/v1/chat/stream");
  }

  private void parseSse(Stream<String> lines, Consumer<GatewayStreamEvent> eventConsumer) {
    SseAccumulator accumulator = new SseAccumulator();
    lines.forEach(line -> consumeLine(line, accumulator, eventConsumer));
    dispatch(accumulator, eventConsumer);
  }

  private void consumeLine(
      String line, SseAccumulator accumulator, Consumer<GatewayStreamEvent> eventConsumer) {
    if (line == null || line.isBlank()) {
      dispatch(accumulator, eventConsumer);
      accumulator.clear();
      return;
    }
    if (line.startsWith("event:")) {
      accumulator.event = line.substring("event:".length()).trim();
      return;
    }
    if (line.startsWith("data:")) {
      if (!accumulator.data.isEmpty()) {
        accumulator.data.append('\n');
      }
      accumulator.data.append(line.substring("data:".length()).trim());
    }
  }

  private void dispatch(SseAccumulator accumulator, Consumer<GatewayStreamEvent> eventConsumer) {
    if (accumulator.data.isEmpty()) {
      return;
    }
    try {
      JsonNode payload = objectMapper.readTree(accumulator.data.toString());
      String type = text(payload, "type");
      eventConsumer.accept(
          new GatewayStreamEvent(
              accumulator.event == null || accumulator.event.isBlank() ? type : accumulator.event,
              type,
              text(payload, "content"),
              integer(payload, "promptTokens"),
              integer(payload, "generationTokens"),
              integer(payload, "totalTokens"),
              payload));
    } catch (Exception e) {
      throw new ProviderGatewayException("Provider gateway returned malformed SSE payload", e);
    }
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isInt() ? value.asInt() : null;
  }

  private static final class SseAccumulator {
    String event;
    final StringBuilder data = new StringBuilder();

    void clear() {
      event = null;
      data.setLength(0);
    }
  }

  public record GatewayMessage(String role, String content) {}

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record GatewayRuntimeOptions(
      Double temperature,
      Double topP,
      Integer topK,
      Integer maxOutputTokens,
      Double frequencyPenalty,
      Double presencePenalty,
      List<String> stopSequences) {}

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record GatewayChatRequest(
      String providerId,
      String modelId,
      List<GatewayMessage> messages,
      String authMethod,
      String apiKey,
      String baseUrl,
      Map<String, String> headers,
      Map<String, String> queryParams,
      GatewayRuntimeOptions options) {

    @Override
    public String toString() {
      return "GatewayChatRequest[providerId=%s, modelId=%s, messages=%d, authMethod=%s, apiKey=%s, baseUrl=%s]"
          .formatted(
              providerId,
              modelId,
              messages == null ? 0 : messages.size(),
              authMethod,
              apiKey == null || apiKey.isBlank() ? null : "[redacted]",
              baseUrl);
    }
  }

  public record GatewayStreamEvent(
      String event,
      String type,
      String content,
      Integer promptTokens,
      Integer generationTokens,
      Integer totalTokens,
      JsonNode payload) {}

  @Getter
  public static class ProviderGatewayException extends RuntimeException {
    public ProviderGatewayException(String message) {
      super(message);
    }

    public ProviderGatewayException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
