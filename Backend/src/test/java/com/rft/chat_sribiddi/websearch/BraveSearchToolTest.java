package com.rft.chat_sribiddi.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BraveSearchToolTest {

  @Mock private HttpClient httpClient;

  @SuppressWarnings("unchecked")
  private final HttpResponse<String> httpResponse = mock(HttpResponse.class);

  private WebSearchProperties properties;
  private BraveSearchTool tool;

  @BeforeEach
  void setUp() {
    properties = new WebSearchProperties();
    properties.setMaxResults(3);
    properties.setRequestTimeout(Duration.ofSeconds(5));
    tool = new BraveSearchTool("test-api-key", httpClient, properties);
  }

  @SuppressWarnings("unchecked")
  private void stubHttpResponse(int statusCode, String body) throws Exception {
    when(httpResponse.statusCode()).thenReturn(statusCode);
    when(httpResponse.body()).thenReturn(body);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
  }

  @Test
  void searchWeb_returnsFormattedResults_whenApiReturnsSuccess() throws Exception {
    String json =
        """
        {
          "web": {
            "results": [
              {"title": "Example", "url": "https://example.com", "description": "An example site."},
              {"title": "Test", "url": "https://test.com", "description": "A test site."}
            ]
          }
        }
        """;
    stubHttpResponse(200, json);

    String result = tool.searchWeb("example query");

    assertThat(result).contains("Web search results:");
    assertThat(result).contains("Example");
    assertThat(result).contains("https://example.com");
    assertThat(result).contains("An example site.");
    assertThat(result).contains("Test");
  }

  @Test
  void searchWeb_returnsErrorMessage_whenApiReturnsNon200() throws Exception {
    stubHttpResponse(401, "");

    String result = tool.searchWeb("test query");

    assertThat(result).isEqualTo("Web search failed (HTTP 401). Please try a different query.");
  }

  @Test
  void searchWeb_returnsNoResults_whenResponseHasEmptyResults() throws Exception {
    stubHttpResponse(200, """
        {"web": {"results": []}}
        """);

    String result = tool.searchWeb("obscure query");

    assertThat(result).isEqualTo("No web results found for this query.");
  }

  @SuppressWarnings("unchecked")
  @Test
  void searchWeb_returnsStableErrorMessage_whenHttpClientThrows() throws Exception {
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Connection refused"));

    String result = tool.searchWeb("test query");

    assertThat(result).isEqualTo("Web search is currently unavailable. Please try again later.");
  }

  @SuppressWarnings("unchecked")
  @Test
  void searchWeb_handlesInterruptedException() throws Exception {
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("interrupted"));

    String result = tool.searchWeb("test query");

    assertThat(result).isEqualTo("Web search was interrupted.");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    // Clear interrupt flag for test runner
    Thread.interrupted();
  }

  @Test
  void searchWeb_respectsMaxResultsFromProperties() throws Exception {
    String json =
        """
        {
          "web": {
            "results": [
              {"title": "R1", "url": "https://r1.com", "description": "d1"},
              {"title": "R2", "url": "https://r2.com", "description": "d2"},
              {"title": "R3", "url": "https://r3.com", "description": "d3"},
              {"title": "R4", "url": "https://r4.com", "description": "d4"},
              {"title": "R5", "url": "https://r5.com", "description": "d5"}
            ]
          }
        }
        """;
    stubHttpResponse(200, json);

    String result = tool.searchWeb("many results");

    // maxResults is set to 3 in setUp
    assertThat(result).contains("R1").contains("R2").contains("R3");
    assertThat(result).doesNotContain("R4").doesNotContain("R5");
  }
}
