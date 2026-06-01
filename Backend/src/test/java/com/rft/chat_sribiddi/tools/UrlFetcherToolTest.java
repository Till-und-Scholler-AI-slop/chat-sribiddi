package com.rft.chat_sribiddi.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.rft.chat_sribiddi.chat.toolcall.ToolConsentContext;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.model.ToolContext;

class UrlFetcherToolTest {

  @RegisterExtension
  static final WireMockExtension WIRE_MOCK =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.wireMockConfig().dynamicPort())
          .build();

  private ToolProperties properties;
  private SimpleMeterRegistry meterRegistry;
  private UrlFetcherTool tool;

  @BeforeEach
  void setUp() {
    properties = new ToolProperties();
    meterRegistry = new SimpleMeterRegistry();
    // Default tool: permissive SSRF checker so WireMock (bound to 127.0.0.1) can be reached.
    tool = new UrlFetcherTool(properties, meterRegistry, uri -> {});
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + WIRE_MOCK.getPort();
  }

  // --- SSRF / scheme validation (real checker) ----------------------------

  @Nested
  class SsrfProtection {

    private final SsrfProtectionUrlSecurityChecker checker = new SsrfProtectionUrlSecurityChecker();

    @Test
    void rejectsFileScheme() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("file:///etc/passwd")))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("http");
    }

    @Test
    void rejectsFtpScheme() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("ftp://example.com/")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsGopherScheme() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("gopher://example.com/")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsLocalhost() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://localhost/")))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("Localhost");
    }

    @Test
    void rejectsLocalhostSubdomain() {
      assertThatThrownBy(
              () -> checker.validateAndResolve(URI.create("http://api.localhost/v1/users")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsLoopbackIpv4() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://127.0.0.1/")))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("private");
    }

    @Test
    void rejectsPrivateRfc1918() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://10.0.0.1/")))
          .isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://192.168.1.1/")))
          .isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://172.16.0.1/")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsLinkLocal() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http://169.254.169.254/")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsUnresolvableHost() {
      assertThatThrownBy(
              () ->
                  checker.validateAndResolve(
                      URI.create("http://this-host-does-not-exist-12345.invalid/")))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("Could not resolve");
    }

    @Test
    void rejectsMissingHost() {
      assertThatThrownBy(() -> checker.validateAndResolve(URI.create("http:///path")))
          .isInstanceOf(SecurityException.class);
    }

    @Test
    void acceptsHttpAndHttpsSchemes() {
      // Just verify no exception is thrown — DNS lookup against a likely-public name.
      // We don't fail if the host is unresolvable in the test env (covered by other tests).
      try {
        checker.validateAndResolve(URI.create("http://example.com/"));
      } catch (SecurityException e) {
        if (e.getMessage() != null && e.getMessage().contains("Could not resolve")) {
          return; // offline test environment, skip
        }
        throw e;
      }
      try {
        checker.validateAndResolve(URI.create("https://example.com/"));
      } catch (SecurityException e) {
        if (e.getMessage() != null && e.getMessage().contains("Could not resolve")) {
          return;
        }
        throw e;
      }
    }
  }

  // --- Tool entry point ----------------------------------------------------

  @Test
  void fetchUrl_blocksPrivateIpOverHttps() {
    UrlFetcherTool realTool =
        new UrlFetcherTool(properties, meterRegistry, new SsrfProtectionUrlSecurityChecker());

    String result = realTool.fetchUrl("https://127.0.0.1/", null, null, null);

    assertThat(result).contains("URL blocked for security reasons");
  }

  @Test
  void fetchUrl_scriptStyleContentStripped() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody(
                        "<h1>Title</h1>"
                            + "<script>alert('xss')</script>"
                            + "<style>body{color:red}</style>"
                            + "<nav>nav text</nav>"
                            + "<footer>footer text</footer>"
                            + "<p>body text</p>")));

    String result = tool.fetchUrl(baseUrl() + "/", null, null, null);

    assertThat(result)
        .contains("Title")
        .contains("body text")
        .doesNotContain("alert('xss')")
        .doesNotContain("color:red")
        .doesNotContain("nav text")
        .doesNotContain("footer text");
  }

  @Test
  void fetchUrl_returnsMarkdownByDefault() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody(
                        "<h1>Title</h1>"
                            + "<p>Hello <a href=\"/x\">link</a></p>"
                            + "<ul><li>one</li><li>two</li></ul>")));

    String result = tool.fetchUrl(baseUrl() + "/", null, null, null);

    assertThat(result)
        .contains("# Title")
        .contains("[link](/x)")
        .contains("one")
        .contains("two");
  }

  @Test
  void fetchUrl_textFormatReturnsPlainText() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<h1>Title</h1><p>Hello <a href=\"/x\">link</a></p>")));

    String result = tool.fetchUrl(baseUrl() + "/", "text", null, null);

    assertThat(result)
        .contains("Title")
        .contains("Hello link")
        .doesNotContain("#")
        .doesNotContain("[link]");
  }

  @Test
  void fetchUrl_truncatesAtConfiguredMaxLength() {
    String body = "<p>" + "x".repeat(1000) + "</p>";
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body)));

    properties.setUrlFetcherMaxContentLength(500);
    String result = tool.fetchUrl(baseUrl() + "/", null, null, null);

    assertThat(result).contains("[Content truncated]");
    assertThat(result.length()).isLessThanOrEqualTo(500 + "[Content truncated]".length() + 5);
  }

  @Test
  void fetchUrl_respectsMaxLengthOverride() {
    String body = "<p>" + "y".repeat(2000) + "</p>";
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body)));

    String result = tool.fetchUrl(baseUrl() + "/", null, 1500, null);

    assertThat(result).contains("[Content truncated]");
  }

  @Test
  void fetchUrl_consentHostEnforcement_Http() {
    ToolContext toolContext =
        new ToolContext(
            Map.of(
                ToolConsentContext.KEY,
                Map.of("url-fetcher", Map.of("approvedHost", "example.com"))));

    String result = tool.fetchUrl("http://blocked.example/path", null, null, toolContext);

    assertThat(result)
        .contains("URL blocked for security reasons")
        .contains("blocked.example")
        .contains("example.com");
  }

  @Test
  void fetchUrl_consentHostEnforcement_Https() {
    ToolContext toolContext =
        new ToolContext(
            Map.of(
                ToolConsentContext.KEY,
                Map.of("url-fetcher", Map.of("approvedHost", "example.com"))));

    String result = tool.fetchUrl("https://blocked.example/path", null, null, toolContext);

    assertThat(result).contains("URL blocked for security reasons");
  }

  @Test
  void fetchUrl_consentHostMatchingAllowsRequest() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200).withBody("<h1>ok</h1>")));

    ToolContext toolContext =
        new ToolContext(
            Map.of(
                ToolConsentContext.KEY,
                Map.of("url-fetcher", Map.of("approvedHost", "127.0.0.1"))));

    String result = tool.fetchUrl(baseUrl() + "/", null, null, toolContext);

    assertThat(result).contains("ok");
  }

  @Test
  void fetchUrl_rateLimitExceeded() {
    properties.setUrlFetcherRateLimitPerMinute(2);
    UrlFetcherTool limitedTool = new UrlFetcherTool(properties, meterRegistry, uri -> {});

    WIRE_MOCK.stubFor(
        get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("<p>ok</p>")));

    ToolContext ctx = new ToolContext(Map.of(UrlFetcherTool.USER_ID_CONTEXT_KEY, "rate-user"));

    assertThat(limitedTool.fetchUrl(baseUrl() + "/", null, null, ctx)).contains("ok");
    assertThat(limitedTool.fetchUrl(baseUrl() + "/", null, null, ctx)).contains("ok");
    String third = limitedTool.fetchUrl(baseUrl() + "/", null, null, ctx);

    assertThat(third).contains("Rate limit exceeded");
  }

  @Test
  void fetchUrl_redirectFollowedSuccessfully() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/start"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/end")));
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/end"))
            .willReturn(aResponse().withStatus(200).withBody("<h1>redirected</h1>")));

    String result = tool.fetchUrl(baseUrl() + "/start", null, null, null);

    assertThat(result).contains("redirected");
  }

  @Test
  void fetchUrl_redirectToPrivateIpBlocked() {
    properties.setUrlFetcherMaxRedirects(5);
    // Permissive-with-exception: allow 127.0.0.1 (WireMock) but block 10.0.0.1
    UrlSecurityChecker conditional =
        uri -> {
          String host = uri.getHost();
          if (host != null && host.startsWith("10.")) {
            throw new SecurityException("URL resolves to a private/reserved IP address.");
          }
        };

    UrlFetcherTool restrictedTool = new UrlFetcherTool(properties, meterRegistry, conditional);

    WIRE_MOCK.stubFor(
        get(urlEqualTo("/start"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "http://10.0.0.1/x")));

    String result = restrictedTool.fetchUrl(baseUrl() + "/start", null, null, null);

    assertThat(result)
        .contains("URL blocked for security reasons")
        .contains("private");
  }

  @Test
  void fetchUrl_maxRedirectsExceeded() {
    properties.setUrlFetcherMaxRedirects(2);
    // Infinite redirect loop
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/loop"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

    String result = tool.fetchUrl(baseUrl() + "/loop", null, null, null);

    assertThat(result).contains("Too many redirects");
  }

  @Test
  void fetchUrl_redirectWithoutLocationHeaderFails() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "")));

    String result = tool.fetchUrl(baseUrl() + "/", null, null, null);

    assertThat(result).contains("Redirect without Location");
  }

  @Test
  void fetchUrl_contentTypeJsonReturnedAsIs() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/data.json"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));

    String result = tool.fetchUrl(baseUrl() + "/data.json", null, null, null);

    assertThat(result).contains("{\"ok\":true}");
  }

  @Test
  void fetchUrl_contentTypePdfRejected() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/file.pdf"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody("%PDF-1.4 fake")));

    String result = tool.fetchUrl(baseUrl() + "/file.pdf", null, null, null);

    assertThat(result).contains("Unsupported content type").contains("pdf");
  }

  @Test
  void fetchUrl_contentTypeTextPlainReturnedAsIs() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/note.txt"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; charset=US-ASCII")
                    .withBody("hello plain text")));

    String result = tool.fetchUrl(baseUrl() + "/note.txt", null, null, null);

    assertThat(result).isEqualTo("hello plain text");
  }

  @Test
  void fetchUrl_encodingHandledFromContentTypeHeader() {
    // ISO-8859-1 encoded "äöü"
    byte[] encoded = { (byte) 0xE4, (byte) 0xF6, (byte) 0xFC };
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/latin1.html"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=ISO-8859-1")
                    .withBody(encoded)));

    String result = tool.fetchUrl(baseUrl() + "/latin1.html", "text", null, null);

    assertThat(result).contains("äöü");
  }

  @Test
  void fetchUrl_htmlTooLargeRejected() {
    properties.setUrlFetcherMaxHtmlSize(1024);
    byte[] big = new byte[2048];
    java.util.Arrays.fill(big, (byte) 'a');
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/big"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody(big)));

    String result = tool.fetchUrl(baseUrl() + "/big", null, null, null);

    assertThat(result).contains("too large");
  }

  @Test
  void fetchUrl_invalidFormatRejected() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("<p>ok</p>")));

    String result = tool.fetchUrl(baseUrl() + "/", "xml", null, null);

    assertThat(result).contains("Invalid format").contains("xml");
  }

  @Test
  void fetchUrl_maxLengthClampedToMinimum() {
    // Body 500 chars; user requests maxLength=100 which clamps UP to 1000 → no truncation.
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200).withBody("<p>" + "z".repeat(500) + "</p>")));

    String result = tool.fetchUrl(baseUrl() + "/", null, 100, null);

    assertThat(result).doesNotContain("[Content truncated]");
  }

  @Test
  void fetchUrl_maxLengthClampedToMaximum() {
    String body = "<p>" + "q".repeat(300_000) + "</p>";
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body)));

    String result = tool.fetchUrl(baseUrl() + "/", null, 500_000, null);

    // maxLength=500000 clamped down to 200000
    assertThat(result).contains("[Content truncated]");
  }

  @Test
  void fetchUrl_httpErrorStatusReturnsFailure() {
    WIRE_MOCK.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

    String result = tool.fetchUrl(baseUrl() + "/missing", null, null, null);

    assertThat(result).contains("HTTP 404");
  }

  @Test
  void fetchUrl_blankUrlReturnsError() {
    String result = tool.fetchUrl("  ", null, null, null);

    assertThat(result).contains("URL is required");
  }

  @Test
  void fetchUrl_recordsMetrics() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("<p>ok</p>")));

    tool.fetchUrl(baseUrl() + "/", null, null, null);

    assertThat(meterRegistry.find("url_fetch_total").tag("status", "200").counter())
        .isNotNull()
        .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
    assertThat(meterRegistry.find("url_fetch_duration").timer()).isNotNull();
  }

  @Test
  void execute_interfaceDispatchesToFetcher() {
    WIRE_MOCK.stubFor(
        get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("<h1>exec</h1>")));

    ToolResult result =
        tool.execute(
            "exec-user", Map.of("url", baseUrl() + "/", "format", "markdown"));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).contains("# exec");
  }

  @Test
  void execute_interfaceRejectsMissingUrl() {
    ToolResult result = tool.execute("u", Map.of());

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("No URL provided");
  }

  @Test
  void execute_interfaceRejectsNullParameters() {
    ToolResult result = tool.execute("u", null);

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("No parameters");
  }

  @Test
  void execute_interfaceRateLimitsPerUser() {
    properties.setUrlFetcherRateLimitPerMinute(1);
    UrlFetcherTool limited = new UrlFetcherTool(properties, meterRegistry, uri -> {});

    WIRE_MOCK.stubFor(
        get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("<p>ok</p>")));

    assertThat(
            limited.execute("u-1", Map.of("url", baseUrl() + "/")).success())
        .isTrue();
    assertThat(
            limited.execute("u-1", Map.of("url", baseUrl() + "/")).error())
        .contains("Rate limit");
  }

  @Test
  void getNameAndRiskAndDisplayName() {
    assertThat(tool.getName()).isEqualTo("url-fetcher");
    assertThat(tool.getDisplayName()).isEqualTo("URL Fetcher");
    assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
  }

  @Test
  void requiresPerCallConsent_returnsFalse() {
    assertThat(tool.requiresPerCallConsent()).isFalse();
  }
}
