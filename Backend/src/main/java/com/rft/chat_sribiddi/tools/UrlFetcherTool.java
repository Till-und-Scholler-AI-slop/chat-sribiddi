package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.chat.toolcall.ToolConsentContext;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * URL fetcher tool that retrieves web page content, converts HTML to Markdown (or plain text),
 * and truncates the result to a configured maximum.
 *
 * <p>Security model:
 *
 * <ul>
 *   <li>Schemes restricted to HTTP and HTTPS via {@link UrlSecurityChecker}.
 *   <li>Private/loopback/link-local/multicast addresses blocked before each request.
 *   <li>Redirects followed manually (max {@link ToolProperties#getUrlFetcherMaxRedirects()}) with
 *       SSRF re-validation on every hop.
 *   <li>Per-user rate limit (sliding 60s window) enforced in {@link #execute(String, Map)} and
 *       optionally in the {@code @Tool} entry point when a {@code userId} is supplied via the
 *       {@link ToolContext}.
 * </ul>
 *
 * <p>Content handling:
 *
 * <ul>
 *   <li>HTML bodies go through {@link Jsoup} for cleanup, then {@link FlexmarkHtmlConverter} for
 *       Markdown conversion (or {@code Document.body().text()} for plain text).
 *   <li>Non-HTML content types (text/plain, application/json) are returned as-is.
 *   <li>Response body must be under {@link ToolProperties#getUrlFetcherMaxHtmlSize()}.
 *   <li>Character encoding is taken from the {@code Content-Type} header with a UTF-8 fallback
 *       (validated against {@link java.nio.charset.Charset#forName(String)}).
 * </ul>
 */
@Slf4j
@Component
public class UrlFetcherTool implements ChatTool {

  /** Key under which {@link PromptToolBinder} puts the active user id on the {@link ToolContext}. */
  public static final String USER_ID_CONTEXT_KEY = "userId";

  private static final String ACCEPTED_CONTENT_PREFIX_HTML = "text/html";
  private static final String ACCEPTED_CONTENT_PREFIX_TEXT = "text/plain";
  private static final String ACCEPTED_CONTENT_PREFIX_JSON = "application/json";
  private static final String CHARSET_ATTRIBUTE_PREFIX = "charset=";
  private static final long RATE_LIMIT_WINDOW_MS = 60_000L;
  private static final String TRUNCATION_MARKER = "\n\n[Content truncated]";

  private final ToolProperties toolProperties;
  private final MeterRegistry meterRegistry;
  private final UrlSecurityChecker securityChecker;
  private final HttpClient fetchClient;
  private final FlexmarkHtmlConverter htmlToMarkdownConverter;
  private final Object rateLimitLock = new Object();
  private volatile long rateLimitWindowStartMs = System.currentTimeMillis();
  private final ConcurrentHashMap<String, LongAdder> rateLimitCounters = new ConcurrentHashMap<>();

  public UrlFetcherTool(
      ToolProperties toolProperties,
      MeterRegistry meterRegistry,
      UrlSecurityChecker securityChecker) {
    this.toolProperties = toolProperties;
    this.meterRegistry = meterRegistry;
    this.securityChecker = securityChecker;
    this.fetchClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(toolProperties.getUrlFetcherTimeout())
            .build();
    this.htmlToMarkdownConverter = FlexmarkHtmlConverter.builder().build();
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
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    if (parameters == null) {
      return ToolResult.failure("No parameters provided.");
    }
    Object urlValue = parameters.get("url");
    if (urlValue == null || urlValue.toString().isBlank()) {
      return ToolResult.failure("No URL provided.");
    }
    String format = readOptionalString(parameters, "format");
    Integer maxLength = readOptionalInt(parameters, "maxLength");
    String resolvedUserId = (userId == null || userId.isBlank()) ? "anonymous" : userId;
    try {
      checkRateLimit(resolvedUserId);
    } catch (SecurityException e) {
      return ToolResult.failure(e.getMessage());
    }
    return fetch(urlValue.toString().trim(), format, maxLength, null, resolvedUserId, 0);
  }

  @Tool(
      description =
          "Fetch the content of a public web page URL and return its text. "
              + "Supports HTTP and HTTPS. HTML is converted to Markdown by default "
              + "(preserves headings, links, lists, code blocks). "
              + "Use format='text' for plain text. "
              + "Content is truncated at the configured max length. "
              + "Timeout: 10 seconds. Max HTML size: 2MB.")
  public String fetchUrl(
      @ToolParam(description = "The HTTP(S) URL to fetch, e.g. 'https://example.com'") String url,
      @ToolParam(
              description = "Output format: 'markdown' (default) or 'text'",
              required = false)
          String format,
      @ToolParam(
              description = "Maximum content length in characters (default 50000, min 1000, max 200000)",
              required = false)
          Integer maxLength,
      ToolContext toolContext) {
    String resolvedUserId = resolveUserId(toolContext);
    try {
      checkRateLimit(resolvedUserId);
    } catch (SecurityException e) {
      return "URL blocked for security reasons: " + e.getMessage();
    }
    ToolResult result = fetch(url, format, maxLength, toolContext, resolvedUserId, 0);
    return result.success() ? result.output() : result.error();
  }

  // --- core fetch pipeline --------------------------------------------------

  private ToolResult fetch(
      String url,
      String format,
      Integer maxLength,
      ToolContext toolContext,
      String userId,
      int redirectCount) {
    long startMs = System.currentTimeMillis();
    try {
      String resolvedFormat = validateFormat(format);
      int resolvedMaxLength = validateMaxLength(maxLength);
      URI uri = normalizeAndParse(url);
      enforceConsent(uri, toolContext);
      securityChecker.validateAndResolve(uri);

      HttpRequest request = buildRequest(uri);
      HttpResponse<byte[]> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        return handleRedirect(uri, response, resolvedFormat, resolvedMaxLength, toolContext, userId, redirectCount);
      }
      if (status < 200 || status >= 300) {
        recordMetrics(url, status, startMs, 0);
        return ToolResult.failure(
            "HTTP %d fetching URL: %s".formatted(status, url));
      }

      String contentTypeHeader =
          response.headers().firstValue("Content-Type").orElse(ACCEPTED_CONTENT_PREFIX_HTML);
      String normalizedType = normalizeContentType(contentTypeHeader);
      if (!isAcceptedContentType(normalizedType)) {
        recordMetrics(url, status, startMs, 0);
        return ToolResult.failure(
            "Unsupported content type: %s. Supported: HTML, plain text, JSON."
                .formatted(contentTypeHeader));
      }

      byte[] bodyBytes = response.body();
      if (bodyBytes.length > toolProperties.getUrlFetcherMaxHtmlSize()) {
        recordMetrics(url, status, startMs, bodyBytes.length);
        return ToolResult.failure(
            "Response body too large (%d bytes). Maximum: %d bytes."
                .formatted(bodyBytes.length, toolProperties.getUrlFetcherMaxHtmlSize()));
      }

      String body = decodeBody(bodyBytes, contentTypeHeader);
      String processed = processBody(body, normalizedType, resolvedFormat, uri);
      String truncated = truncate(processed, resolvedMaxLength);

      recordMetrics(url, status, startMs, bodyBytes.length);
      return ToolResult.success(truncated);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Request was interrupted.");
    } catch (SecurityException e) {
      return ToolResult.failure("URL blocked for security reasons: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("Invalid request: " + e.getMessage());
    } catch (Exception e) {
      log.warn("Failed to fetch URL: {}", url, e);
      recordMetrics(url, -1, startMs, 0);
      return ToolResult.failure("Failed to fetch URL: " + e.getMessage());
    }
  }

  private ToolResult handleRedirect(
      URI baseUri,
      HttpResponse<byte[]> response,
      String format,
      int maxLength,
      ToolContext toolContext,
      String userId,
      int redirectCount) {
    int maxRedirects = toolProperties.getUrlFetcherMaxRedirects();
    if (redirectCount >= maxRedirects) {
      return ToolResult.failure("Too many redirects (max " + maxRedirects + ").");
    }
    String location = response.headers().firstValue("Location").orElse(null);
    if (location == null || location.isBlank()) {
      return ToolResult.failure("Redirect without Location header.");
    }
    URI nextUri;
    try {
      nextUri = resolveRedirectUri(baseUri, location);
    } catch (IllegalArgumentException e) {
      return ToolResult.failure("Invalid redirect Location: " + e.getMessage());
    }
    try {
      enforceConsent(nextUri, toolContext);
      securityChecker.validateAndResolve(nextUri);
    } catch (SecurityException e) {
      return ToolResult.failure("URL blocked for security reasons: " + e.getMessage());
    }
    return fetch(nextUri.toString(), format, maxLength, toolContext, userId, redirectCount + 1);
  }

  // --- helpers --------------------------------------------------------------

  private URI normalizeAndParse(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("URL is required.");
    }
    String trimmed = url.trim();
    String withScheme =
        (trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8))
            ? trimmed
            : "https://" + trimmed;
    try {
      return URI.create(withScheme);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
    }
  }

  private HttpRequest buildRequest(URI uri) {
    return HttpRequest.newBuilder()
        .uri(uri)
        .header("User-Agent", toolProperties.getUrlFetcherUserAgent())
        .header("Accept",
            "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
        .timeout(toolProperties.getUrlFetcherTimeout())
        .GET()
        .build();
  }

  private void enforceConsent(URI uri, ToolContext toolContext) {
    Map<String, Object> args = ToolConsentContext.argsFor(toolContext, getName());
    Object approvedHost = args.get("approvedHost");
    if (approvedHost == null || approvedHost.toString().isBlank()) {
      return;
    }
    String host = uri.getHost();
    String expected = approvedHost.toString().trim();
    if (host == null || !host.equalsIgnoreCase(expected)) {
      throw new SecurityException(
          "URL host '%s' does not match the approved host '%s'."
              .formatted(host == null ? "" : host, expected));
    }
  }

  private void checkRateLimit(String userId) {
    if (userId == null || userId.isBlank()) {
      return;
    }
    int limit = toolProperties.getUrlFetcherRateLimitPerMinute();
    if (limit <= 0) {
      return;
    }
    synchronized (rateLimitLock) {
      long now = System.currentTimeMillis();
      if (now - rateLimitWindowStartMs > RATE_LIMIT_WINDOW_MS) {
        rateLimitCounters.clear();
        rateLimitWindowStartMs = now;
      }
      LongAdder counter = rateLimitCounters.computeIfAbsent(userId, k -> new LongAdder());
      long count = counter.sum();
      if (count >= limit) {
        throw new SecurityException(
            "Rate limit exceeded (%d requests per minute).".formatted(limit));
      }
      counter.increment();
    }
  }

  private String resolveUserId(ToolContext toolContext) {
    if (toolContext == null || toolContext.getContext() == null) {
      return "anonymous";
    }
    Object userId = toolContext.getContext().get(USER_ID_CONTEXT_KEY);
    if (userId == null) {
      return "anonymous";
    }
    String value = userId.toString();
    return value.isBlank() ? "anonymous" : value;
  }

  private String validateFormat(String format) {
    if (format == null || format.isBlank()) {
      return toolProperties.getUrlFetcherDefaultFormat();
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (!"markdown".equals(normalized) && !"text".equals(normalized)) {
      throw new IllegalArgumentException(
          "Invalid format: '" + format + "'. Must be 'markdown' or 'text'.");
    }
    return normalized;
  }

  private int validateMaxLength(Integer maxLength) {
    if (maxLength == null) {
      return toolProperties.getUrlFetcherMaxContentLength();
    }
    return Math.max(1_000, Math.min(200_000, maxLength));
  }

  private URI resolveRedirectUri(URI baseUri, String location) {
    URI locationUri = URI.create(location.trim());
    if (locationUri.isAbsolute()) {
      return locationUri;
    }
    return baseUri.resolve(locationUri);
  }

  private String decodeBody(byte[] bodyBytes, String contentTypeHeader) {
    Charset charset = extractCharset(contentTypeHeader);
    if (charset != null) {
      return new String(bodyBytes, charset);
    }
    String utf8 = new String(bodyBytes, StandardCharsets.UTF_8);
    if (isValidUtf8(bodyBytes)) {
      return utf8;
    }
    return new String(bodyBytes, StandardCharsets.ISO_8859_1);
  }

  private Charset extractCharset(String contentTypeHeader) {
    if (contentTypeHeader == null) {
      return null;
    }
    for (String part : contentTypeHeader.split(";")) {
      String trimmed = part.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith(CHARSET_ATTRIBUTE_PREFIX)) {
        String charsetName = trimmed.substring(CHARSET_ATTRIBUTE_PREFIX.length()).trim();
        if (charsetName.startsWith("\"") && charsetName.endsWith("\"")) {
          charsetName = charsetName.substring(1, charsetName.length() - 1);
        }
        try {
          return Charset.forName(charsetName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
          return null;
        }
      }
    }
    return null;
  }

  private boolean isValidUtf8(byte[] bytes) {
    java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
    try {
      decoder.decode(java.nio.ByteBuffer.wrap(bytes));
      return true;
    } catch (java.nio.charset.CharacterCodingException e) {
      return false;
    }
  }

  private String normalizeContentType(String contentTypeHeader) {
    if (contentTypeHeader == null) {
      return ACCEPTED_CONTENT_PREFIX_HTML;
    }
    int semicolon = contentTypeHeader.indexOf(';');
    String main =
        semicolon >= 0
            ? contentTypeHeader.substring(0, semicolon)
            : contentTypeHeader;
    return main.trim().toLowerCase(Locale.ROOT);
  }

  private boolean isAcceptedContentType(String normalized) {
    return normalized.equals(ACCEPTED_CONTENT_PREFIX_HTML)
        || normalized.equals(ACCEPTED_CONTENT_PREFIX_TEXT)
        || normalized.equals(ACCEPTED_CONTENT_PREFIX_JSON);
  }

  private String processBody(String body, String normalizedType, String format, URI baseUri) {
    if (ACCEPTED_CONTENT_PREFIX_JSON.equals(normalizedType)
        || ACCEPTED_CONTENT_PREFIX_TEXT.equals(normalizedType)) {
      return body;
    }
    if ("text".equals(format)) {
      return htmlToText(body, baseUri);
    }
    return htmlToMarkdown(body, baseUri);
  }

  private String htmlToText(String html, URI baseUri) {
    Document doc = Jsoup.parse(html, baseUri.toString());
    doc.select("script,style,noscript,nav,footer,header,aside").remove();
    String text = Objects.toString(doc.body().text(), "");
    return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
  }

  private String htmlToMarkdown(String html, URI baseUri) {
    Document doc = Jsoup.parse(html, baseUri.toString());
    doc.select("script,style,noscript,nav,footer,header,aside").remove();
    String cleaned = doc.body().html();
    try {
      String markdown = htmlToMarkdownConverter.convert(cleaned);
      return convertSetextToAtx(markdown);
    } catch (RuntimeException e) {
      log.warn("Markdown conversion failed, falling back to plain text: {}", e.getMessage());
      return Objects.toString(doc.body().text(), "");
    }
  }

  /**
   * Convert flexmark's default Setext-style headings ("Title\n=====\n") to ATX-style ("# Title\n"),
   * which is the format LLMs and most Markdown renderers treat as canonical. Setext is ambiguous
   * because underlines can be confused with horizontal rules.
   */
  private static String convertSetextToAtx(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return markdown;
    }
    String[] lines = markdown.split("\n", -1);
    StringBuilder out = new StringBuilder(markdown.length() + 32);
    for (int i = 0; i < lines.length; i++) {
      if (i + 1 < lines.length) {
        String next = lines[i + 1].trim();
        if (!next.isEmpty() && next.chars().allMatch(c -> c == '=')) {
          out.append("# ").append(lines[i]).append('\n');
          i++;
          continue;
        }
        if (!next.isEmpty() && next.chars().allMatch(c -> c == '-') && next.length() >= 3) {
          // Only treat as Setext h2 if the previous line is non-empty (avoids converting HRs).
          if (lines[i].length() > 0) {
            out.append("## ").append(lines[i]).append('\n');
            i++;
            continue;
          }
        }
      }
      out.append(lines[i]).append('\n');
    }
    return out.toString().stripTrailing();
  }

  private String truncate(String text, int maxLength) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength) + TRUNCATION_MARKER;
  }

  private void recordMetrics(String url, int statusCode, long startMs, int contentLength) {
    long durationMs = Math.max(0, System.currentTimeMillis() - startMs);
    log.info(
        "URL fetch: url={}, status={}, duration={}ms, contentLength={}",
        url,
        statusCode,
        durationMs,
        contentLength);
    try {
      meterRegistry.counter("url_fetch_total", "status", String.valueOf(statusCode)).increment();
      Timer.builder("url_fetch_duration")
          .description("Time spent fetching a URL via the URL fetcher tool")
          .register(meterRegistry)
          .record(durationMs, TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      log.debug("Failed to record URL fetch metrics: {}", e.getMessage());
    }
  }

  private static String readOptionalString(Map<String, Object> parameters, String key) {
    Object value = parameters.get(key);
    return value == null ? null : value.toString();
  }

  private static Integer readOptionalInt(Map<String, Object> parameters, String key) {
    Object value = parameters.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
