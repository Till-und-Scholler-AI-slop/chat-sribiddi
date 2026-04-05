package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.net.InetAddress;
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
 * and truncates to a maximum size. Includes SSRF protection.
 */
@Component
public class UrlFetcherTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(UrlFetcherTool.class);

  private static final int MAX_CONTENT_LENGTH = 50_000;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  public UrlFetcherTool(HttpClient httpClient) {
    this.httpClient = httpClient;
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
      InetAddress resolvedIp = validateUrlAndResolve(uri);

      // Build request with the validated IP as the host to prevent DNS rebinding
      String ipHost = resolvedIp.getHostAddress();
      int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
      URI ipUri = new URI(uri.getScheme(), null, ipHost, port, uri.getPath(), uri.getQuery(), uri.getFragment());

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(ipUri)
              .header("Host", uri.getHost())
              .header("User-Agent", "ChatSribiddi/1.0 (Tool)")
              .header("Accept", "text/html,text/plain,application/json")
              .timeout(TIMEOUT)
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      // Post-request verification: ensure the connection was actually to the validated IP
      // (HttpClient doesn't expose the connected address, but the request was made to the IP directly)

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
    } catch (SecurityException e) {
      return ToolResult.failure("URL blocked for security reasons: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("Invalid URL: " + e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to fetch URL: {}", url, e);
      return ToolResult.failure("Failed to fetch URL: " + e.getMessage());
    }
  }

  /**
   * Validates the URL scheme, host, and resolves DNS.
   * Returns the first non-private InetAddress to prevent DNS rebinding (TOCTOU).
   */
  private InetAddress validateUrlAndResolve(URI uri) {
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new SecurityException("Only http and https protocols are allowed.");
    }
    String host = uri.getHost();
    if (host == null) {
      throw new SecurityException("URL must have a host.");
    }
    String lowerHost = host.toLowerCase();
    if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
        || lowerHost.equals("ip6-localhost") || lowerHost.equals("ip6-loopback")) {
      throw new SecurityException("Localhost URLs are not allowed.");
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      for (InetAddress addr : addresses) {
        if (isPrivateIp(addr)) {
          throw new SecurityException("URL resolves to a private/reserved IP address.");
        }
      }
      // Return the first resolved address for direct IP-based request
      return addresses[0];
    } catch (SecurityException e) {
      throw e;
    } catch (Exception e) {
      throw new SecurityException("Could not resolve host: " + host);
    }
  }

  private boolean isPrivateIp(InetAddress addr) {
    byte[] bytes = addr.getAddress();
    if (bytes.length == 4) {
      if ((bytes[0] & 0xFF) == 127) return true;
      if ((bytes[0] & 0xFF) == 10) return true;
      if ((bytes[0] & 0xFF) == 172 && (bytes[1] & 0xF0) == 16) return true;
      if ((bytes[0] & 0xFF) == 192 && (bytes[1] & 0xFF) == 168) return true;
      if ((bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254) return true;
      if ((bytes[0] & 0xFF) == 0) return true;
    }
    return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
  }

  /**
   * Sequential HTML-to-text converter. Removes script/style/nav/footer/header blocks,
   * strips remaining tags, decodes common entities, and normalizes whitespace.
   * No regex with .*? patterns — uses single-pass character iteration to avoid
   * catastrophic backtracking on malformed HTML.
   */
  private String htmlToText(String html) {
    StringBuilder sb = new StringBuilder(html.length() / 2);
    int i = 0;
    int len = html.length();
    boolean inScript = false;
    boolean inStyle = false;
    boolean inNav = false;
    boolean inFooter = false;
    boolean inHeader = false;
    int skipDepth = 0;

    while (i < len) {
      if (skipDepth > 0) {
        if (html.charAt(i) == '<' && i + 1 < len && html.charAt(i + 1) == '/') {
          int tagEnd = html.indexOf('>', i + 2);
          if (tagEnd > 0) {
            String closingTag = html.substring(i + 2, tagEnd).trim().toLowerCase();
            if (closingTag.startsWith("script") && inScript) { skipDepth--; inScript = false; }
            else if (closingTag.startsWith("style") && inStyle) { skipDepth--; inStyle = false; }
            else if (closingTag.startsWith("nav") && inNav) { skipDepth--; inNav = false; }
            else if (closingTag.startsWith("footer") && inFooter) { skipDepth--; inFooter = false; }
            else if (closingTag.startsWith("header") && inHeader) { skipDepth--; inHeader = false; }
            i = tagEnd + 1;
            continue;
          }
        }
        i++;
        continue;
      }

      char c = html.charAt(i);
      if (c == '<') {
        int tagEnd = html.indexOf('>', i + 1);
        if (tagEnd < 0) { i++; continue; }
        String tag = html.substring(i + 1, tagEnd).trim().toLowerCase();
        boolean selfClosing = tag.endsWith("/");
        if (selfClosing) tag = tag.substring(0, tag.length() - 1).trim();

        if (tag.startsWith("script")) { inScript = true; skipDepth++; i = tagEnd + 1; continue; }
        if (tag.startsWith("style")) { inStyle = true; skipDepth++; i = tagEnd + 1; continue; }
        if (tag.startsWith("noscript")) { skipDepth++; i = tagEnd + 1; continue; }
        if (tag.startsWith("nav")) { inNav = true; skipDepth++; i = tagEnd + 1; continue; }
        if (tag.startsWith("footer")) { inFooter = true; skipDepth++; i = tagEnd + 1; continue; }
        if (tag.startsWith("header")) { inHeader = true; skipDepth++; i = tagEnd + 1; continue; }

        if (tag.startsWith("br") || tag.startsWith("p") || tag.startsWith("div")
            || tag.startsWith("h") || tag.startsWith("li") || tag.startsWith("tr")) {
          sb.append('\n');
        }
        i = tagEnd + 1;
        continue;
      }

      if (c == '&') {
        int semi = html.indexOf(';', i);
        if (semi > 0 && semi - i < 10) {
          String entity = html.substring(i, semi + 1);
          switch (entity) {
            case "&nbsp;" -> sb.append(' ');
            case "&amp;" -> sb.append('&');
            case "&lt;" -> sb.append('<');
            case "&gt;" -> sb.append('>');
            case "&quot;" -> sb.append('"');
            case "&#39;" -> sb.append('\'');
            default -> sb.append(c);
          }
          i = semi + 1;
          continue;
        }
      }

      sb.append(c);
      i++;
    }

    String text = sb.toString();
    text = text.replaceAll("[ \\t]+", " ");
    text = text.replaceAll("\n{3,}", "\n\n");
    return text.trim();
  }
}
