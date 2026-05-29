package com.rft.chat_sribiddi.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.toolcall.ToolConsentContext;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class UrlFetcherToolTest {

  private final UrlFetcherTool tool = new UrlFetcherTool(HttpClient.newHttpClient(), "test-agent");

  @Test
  void fetchUrl_rejectsUrlOutsideApprovedToolContextHost() {
    ToolContext toolContext =
        new ToolContext(
            Map.of(
                ToolConsentContext.KEY,
                Map.of("url-fetcher", Map.of("approvedHost", "example.com"))));

    String result = tool.fetchUrl("http://blocked.example/path", toolContext);

    assertThat(result)
        .contains("URL blocked for security reasons")
        .contains("blocked.example")
        .contains("example.com");
  }
}
