package com.rft.chat_sribiddi.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayChatRequest;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayMessage;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayStreamEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderGatewayClientTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void streamChat_postsGatewayRequestAndParsesSseEvents() throws Exception {
    List<String> requestBodies = new ArrayList<>();
    startGateway(requestBodies);
    ProviderGatewayProperties properties = new ProviderGatewayProperties();
    properties.setEnabled(true);
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    ProviderGatewayClient client = new ProviderGatewayClient(properties, new ObjectMapper());
    List<GatewayStreamEvent> events = new ArrayList<>();

    client.streamChat(
        new GatewayChatRequest(
            "mock",
            "mock-model",
            List.of(new GatewayMessage("user", "hello")),
            "API_KEY",
            "sk-test",
            "https://example.invalid/v1",
            null,
            null,
            null),
        events::add);

    assertThat(requestBodies).singleElement().satisfies(body -> {
      assertThat(body).contains("\"providerId\":\"mock\"");
      assertThat(body).contains("\"apiKey\":\"sk-test\"");
    });
    assertThat(events)
        .extracting(GatewayStreamEvent::content)
        .containsExactly("hello", null);
    assertThat(events.getLast().event()).isEqualTo("done");
    assertThat(events.getLast().promptTokens()).isEqualTo(2);
    assertThat(events.getLast().generationTokens()).isEqualTo(1);
    assertThat(events.getLast().totalTokens()).isEqualTo(3);
  }

  @Test
  void gatewayRequestToStringRedactsApiKey() {
    GatewayChatRequest request =
        new GatewayChatRequest(
            "openai-compatible",
            "model",
            List.of(new GatewayMessage("user", "hello")),
            "API_KEY",
            "sk-secret",
            "https://example.invalid/v1",
            null,
            null,
            null);

    assertThat(request.toString()).contains("[redacted]").doesNotContain("sk-secret");
  }

  private void startGateway(List<String> requestBodies) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/stream",
        exchange -> {
          requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              """
              event: message
              data: {"type":"text","content":"hello"}

              event: done
              data: {"type":"done","promptTokens":2,"generationTokens":1,"totalTokens":3}

              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("content-type", "text/event-stream; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }
}
