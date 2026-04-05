package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * URL fetcher tool that retrieves web page content, converts HTML to plain text,
 * and truncates to a maximum size.
 */
@Component
public class UrlFetcherTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(UrlFetcherTool.class);

  private static final int MAX_CONTENT_LENGTH = 50_000;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  public UrlFetcherTool() {
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public String getName() {
    return "url-fetcher";
  }

  @Override
  public String getDisplayName() {
    return "URL Fetcher";
  }

  @Override
  public String getDescription() {
    return "Fetch and read the content of a web page URL. "
        + "Returns the page text content (HTML stripped). "
        + "Use this when the user wants you to read or summarize a specific webpage.";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String url =
        parameters != null && parameters.containsKey("url")
            ? parameters.get("url").toString()
            : null;
    if (url == null || url.isBlank()) {
      return ToolResult.failure("No URL provided.");
    }
    return fetch(url.trim());
  }

  @Tool(
      description =
          "Fetch the content of a web page URL and return its text. "
              + "HTML is stripped; content is truncated at 50KB. Timeout: 10 seconds.")
  public String fetchUrl(
      @ToolParam(description = "The URL to fetch, e.g. 'https://example.com'") String url) {
    ToolResult result = fetch(url);
    return result.success() ? result.output() : result.error();
  }

  private ToolResult fetch(String url) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      url = "https://" + url;
    }

    try {
      URI uri = URI.create(url);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(uri)
              .header("User-Agent", "ChatSribiddi/1.0 (Tool)")
              .header("Accept", "text/html,text/plain,application/json")
              .timeout(TIMEOUT)
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        return ToolResult.failure(
            "HTTP %d fetching URL: %s".formatted(response.statusCode(), url));
      }

      String body = response.body();
      String text = htmlToText(body);

      if (text.length() > MAX_CONTENT_LENGTH) {
        text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n[Content truncated at 50KB]";
      }

      return ToolResult.success(text);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Request was interrupted.");
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("Invalid URL: " + e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to fetch URL: {}", url, e);
      return ToolResult.failure("Failed to fetch URL: " + e.getMessage());
    }
  }

  private String htmlToText(String html) {
    // Simple HTML to text: remove script/style blocks, strip tags, normalize whitespace
    String text = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", "");
    text = text.replaceAll("(?is)<nav[^>]*>.*?</nav>", "");
    text = text.replaceAll("(?is)<footer[^>]*>.*?</footer>", "");
    text = text.replaceAll("(?is)<header[^>]*>.*?</header>", "");
    text = text.replaceAll("<br\s*/?>", "\n");
    text = text.replaceAll("</?(p|div|h[1-6]|li|tr)[^>]*>", "\n");
    text = text.replaceAll("<[^>]+>", "");
    text = text.replaceAll("&nbsp;", " ");
    text = text.replaceAll("&amp;", "&");
    text = text.replaceAll("&lt;", "<");
    text = text.replaceAll("&gt;", ">");
    text = text.replaceAll("&quot;", "\"");
    text = text.replaceAll("&#39;", "'");
    text = text.replaceAll("[ \t]+", " ");
    text = text.replaceAll("\n{3,}", "\n\n");
    return text.trim();
  }
}
