package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.ChatRuntimeOptions;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatOptions;

class DefaultProviderClientFactoryTest {

  private DefaultProviderClientFactory factory;

  @BeforeEach
  void setUp() {
    OpenAiProviderProperties props = new OpenAiProviderProperties();
    factory =
        new DefaultProviderClientFactory(
            props,
            new GoogleProviderProperties(),
            new OpenRouterProviderProperties(),
            ObservationRegistry.NOOP);
  }

  @Test
  void getClient_google_returnsInitializedClientHandle() {
    ProviderClientHandle holder = factory.getClient(Provider.GOOGLE, "fake-api-key");

    assertThat(holder).isNotNull();
    assertThat(holder.client()).isNotNull();
    assertThat(holder.closeable()).isNull();
  }

  @Test
  void getClient_google_cachedHandle_returnsSameInstanceForSameKey() {
    ProviderClientHandle first = factory.getClient(Provider.GOOGLE, "fake-api-key");
    first.close(); // must not destroy the cached ProviderClientHandle

    ProviderClientHandle second = factory.getClient(Provider.GOOGLE, "fake-api-key");

    assertThat(second).isSameAs(first);
    assertThat(second.client()).isSameAs(first.client());
  }

  @Test
  void getClient_google_differentKeys_returnSeparateHoldersWithDistinctClients() {
    ProviderClientHandle holder1 = factory.getClient(Provider.GOOGLE, "key-alpha");
    ProviderClientHandle holder2 = factory.getClient(Provider.GOOGLE, "key-beta");

    assertThat(holder1).isNotSameAs(holder2);
    assertThat(holder1.client()).isNotSameAs(holder2.client());
  }

  @Test
  void getClient_googleOauth_returnsInitializedClientHandleWithoutApiKey() {
    ResolvedCredential credential =
        new ResolvedCredential(
            null,
            CredentialSource.USER_OAUTH,
            new com.rft.chat_sribiddi.access.AccessProperties().authenticatedLimits(),
            "access-token",
            "refresh-token",
            "client-id",
            "client-secret",
            "quota-project",
            Instant.now().plusSeconds(3600),
            "google",
            42L);

    ProviderClientHandle holder = factory.getClient(Provider.GOOGLE, credential);

    assertThat(holder).isNotNull();
    assertThat(holder.client()).isNotNull();
    assertThat(holder.closeable()).isNull();
  }

  @Test
  void buildOptions_openRouter_usesOpenAiCompatibleOptions() {
    var options = factory.buildOptions(Provider.OPENROUTER, "openai/gpt-4o-mini", "or-key");

    assertThat(options).isInstanceOf(OpenAiChatOptions.class);
    OpenAiChatOptions openAiOptions = (OpenAiChatOptions) options;
    assertThat(openAiOptions.getModel()).isEqualTo("openai/gpt-4o-mini");
    assertThat(openAiOptions.getStreamOptions()).isNotNull();
    assertThat(openAiOptions.getStreamOptions().includeUsage()).isTrue();
  }

  @Test
  void buildOptions_appliesPortableSpringAiRuntimeOptions() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(0.2, 0.8, 40, 500, -0.1, 0.3, List.of("END"));

    var options = factory.buildOptions(Provider.OPENAI, "gpt-5.2", "sk-key", runtimeOptions);

    assertThat(options).isInstanceOf(OpenAiChatOptions.class);
    assertThat(options.getModel()).isEqualTo("gpt-5.2");
    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(options.getTopP()).isEqualTo(0.8);
    assertThat(options.getTopK()).isNull();
    assertThat(options.getMaxTokens()).isEqualTo(500);
    assertThat(options.getFrequencyPenalty()).isEqualTo(-0.1);
    assertThat(options.getPresencePenalty()).isEqualTo(0.3);
    assertThat(options.getStopSequences()).containsExactly("END");
    OpenAiChatOptions openAiOptions = (OpenAiChatOptions) options;
    assertThat(openAiOptions.getStreamOptions()).isNotNull();
    assertThat(openAiOptions.getStreamOptions().includeUsage()).isTrue();
  }

  @Test
  void buildOptions_appliesOpenAiSpecificSpringAiRuntimeOptions() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            "medium",
            "high",
            "auto");

    var options =
        factory.buildOptions(Provider.OPENROUTER, "openai/gpt-5.2", "or-key", runtimeOptions);

    assertThat(options).isInstanceOf(OpenAiChatOptions.class);
    OpenAiChatOptions openAiOptions = (OpenAiChatOptions) options;
    assertThat(openAiOptions.getReasoningEffort()).isEqualTo("medium");
    assertThat(openAiOptions.getVerbosity()).isEqualTo("high");
    assertThat(openAiOptions.getServiceTier()).isEqualTo("auto");
  }

  @Test
  void buildOptions_appliesTopKForGoogleRuntimeOptions() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(0.2, 0.8, 40, 500, null, null, List.of());

    var options =
        factory.buildOptions(Provider.GOOGLE, "gemini-2.5-flash", "goog-key", runtimeOptions);

    assertThat(options.getModel()).isEqualTo("gemini-2.5-flash");
    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(options.getTopP()).isEqualTo(0.8);
    assertThat(options.getTopK()).isEqualTo(40);
    assertThat(options.getMaxTokens()).isEqualTo(500);
  }

  @Test
  void buildOptions_appliesGoogleGenAiSpecificSpringAiRuntimeOptions() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            8192,
            null,
            true,
            true,
            true,
            true);

    var options =
        factory.buildOptions(Provider.GOOGLE, "gemini-2.5-flash", "goog-key", runtimeOptions);

    assertThat(options).isInstanceOf(GoogleGenAiChatOptions.class);
    GoogleGenAiChatOptions googleOptions = (GoogleGenAiChatOptions) options;
    assertThat(googleOptions.getThinkingBudget()).isEqualTo(8192);
    assertThat(googleOptions.getIncludeThoughts()).isTrue();
    assertThat(googleOptions.getGoogleSearchRetrieval()).isTrue();
    assertThat(googleOptions.getIncludeServerSideToolInvocations()).isTrue();
    assertThat(googleOptions.getIncludeExtendedUsageMetadata()).isTrue();
  }

  @Test
  void buildOptions_appliesGoogleGenAiThinkingLevel() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            GoogleGenAiThinkingLevel.HIGH,
            true,
            null,
            null);

    var options =
        factory.buildOptions(Provider.GOOGLE, "gemini-2.5-flash", "goog-key", runtimeOptions);

    GoogleGenAiChatOptions googleOptions = (GoogleGenAiChatOptions) options;
    assertThat(googleOptions.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
    assertThat(googleOptions.getIncludeThoughts()).isTrue();
  }

  @Test
  void buildOptions_rejectsConflictingGoogleThinkingControls() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            8192,
            GoogleGenAiThinkingLevel.HIGH,
            null,
            null,
            null);

    assertThatThrownBy(
            () ->
                factory.buildOptions(
                    Provider.GOOGLE, "gemini-2.5-flash", "goog-key", runtimeOptions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinkingBudget and thinkingLevel");
  }

  @Test
  void buildOptions_appliesAnthropicThinkingBudget() {
    ChatRuntimeOptions runtimeOptions =
        new ChatRuntimeOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            4096,
            null,
            true,
            null,
            null);

    var options =
        factory.buildOptions(Provider.CLAUDE, "claude-sonnet-4-5", "claude-key", runtimeOptions);

    assertThat(options).isInstanceOf(AnthropicChatOptions.class);
    AnthropicChatOptions anthropicOptions = (AnthropicChatOptions) options;
    assertThat(anthropicOptions.getThinking()).isNotNull();
    assertThat(anthropicOptions.getThinking().asEnabled().budgetTokens()).isEqualTo(4096);
    assertThat(anthropicOptions.getThinking().asEnabled().display())
        .contains(com.anthropic.models.messages.ThinkingConfigEnabled.Display.SUMMARIZED);
  }

  @Test
  void getClient_openRouterStreaming_usesConfiguredAsyncClientHeaders() throws Exception {
    OpenRouterProviderProperties openRouter = new OpenRouterProviderProperties();
    openRouter.setAppName("chat-sribiddi-test");
    openRouter.setReferrer("https://example.invalid/chat");
    try (CapturingServer server = CapturingServer.start(openAiStreamResponse())) {
      openRouter.setBaseUrl(server.baseUrl() + "/api/v1");
      DefaultProviderClientFactory customFactory =
          new DefaultProviderClientFactory(
              new OpenAiProviderProperties(),
              new GoogleProviderProperties(),
              openRouter,
              ObservationRegistry.NOOP);

      ProviderClientHandle handle =
          customFactory.getClient(Provider.OPENROUTER, "or-key", CredentialSource.USER_KEY);
      try (handle) {
        var options = customFactory.buildOptions(Provider.OPENROUTER, "test-model", "or-key");
        var chunks =
            handle
                .client()
                .prompt()
                .options(options.mutate())
                .messages(List.of(new UserMessage("hi")))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull().isNotEmpty();
      }

      assertThat(server.capturedPath()).isEqualTo("/api/v1/chat/completions");
      assertThat(server.capturedAuthorization()).isEqualTo("Bearer or-key");
      assertThat(server.capturedReferrer()).isEqualTo("https://example.invalid/chat");
      assertThat(server.capturedTitle()).isEqualTo("chat-sribiddi-test");
    }
  }

  @Test
  void buildGoogleHttpOptions_usesConfiguredTimeoutMillis() {
    OpenAiProviderProperties openAiProperties = new OpenAiProviderProperties();
    GoogleProviderProperties googleProperties = new GoogleProviderProperties();
    googleProperties.setTimeout(Duration.ofSeconds(123));
    DefaultProviderClientFactory customFactory =
        new DefaultProviderClientFactory(
            openAiProperties,
            googleProperties,
            new OpenRouterProviderProperties(),
            ObservationRegistry.NOOP);

    var options = customFactory.buildGoogleHttpOptions();

    assertThat(options.timeout()).contains(123_000);
  }

  @Test
  void getClient_sameKey_differentCredentialSources_returnSeparateHolders() {
    ProviderClientHandle byok =
        factory.getClient(Provider.GOOGLE, "shared-key", CredentialSource.USER_KEY);
    ProviderClientHandle guest =
        factory.getClient(Provider.GOOGLE, "shared-key", CredentialSource.GUEST_OPENROUTER_FREE);

    assertThat(guest).isNotSameAs(byok);
    assertThat(guest.client()).isNotSameAs(byok.client());

    // Subsequent calls with the same source+key must still hit the cached entry.
    ProviderClientHandle byokAgain =
        factory.getClient(Provider.GOOGLE, "shared-key", CredentialSource.USER_KEY);
    assertThat(byokAgain).isSameAs(byok);
  }

  @Test
  void getClient_twoArgOverload_defaultsToUserKeyCachePartition() {
    ProviderClientHandle legacy = factory.getClient(Provider.GOOGLE, "byok-key");
    ProviderClientHandle explicit =
        factory.getClient(Provider.GOOGLE, "byok-key", CredentialSource.USER_KEY);

    assertThat(explicit).isSameAs(legacy);
  }

  @Test
  void getClient_nullCredentialSource_throws() {
    assertThatThrownBy(() -> factory.getClient(Provider.OPENAI, "key", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildOpenRouterAttributionHeaders_includesReferrerAndAppName() {
    OpenRouterProviderProperties openRouter = new OpenRouterProviderProperties();
    openRouter.setAppName("chat-sribiddi-test");
    openRouter.setReferrer("https://example.invalid/chat");
    DefaultProviderClientFactory custom =
        new DefaultProviderClientFactory(
            new OpenAiProviderProperties(),
            new GoogleProviderProperties(),
            openRouter,
            ObservationRegistry.NOOP);

    var headers = custom.buildOpenRouterAttributionHeaders();

    assertThat(headers.getFirst("HTTP-Referer")).isEqualTo("https://example.invalid/chat");
    assertThat(headers.getFirst("X-Title")).isEqualTo("chat-sribiddi-test");
  }

  @Test
  void buildOpenRouterAttributionHeaders_skipsBlankConfigValues() {
    OpenRouterProviderProperties openRouter = new OpenRouterProviderProperties();
    openRouter.setAppName("");
    openRouter.setReferrer("   ");
    DefaultProviderClientFactory custom =
        new DefaultProviderClientFactory(
            new OpenAiProviderProperties(),
            new GoogleProviderProperties(),
            openRouter,
            ObservationRegistry.NOOP);

    var headers = custom.buildOpenRouterAttributionHeaders();

    assertThat(headers.getFirst("HTTP-Referer")).isNull();
    assertThat(headers.getFirst("X-Title")).isNull();
  }

  private static String openAiStreamResponse() {
    return """
        data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

        data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

        data: [DONE]

        """;
  }

  private record CapturingServer(
      HttpServer server,
      String baseUrl,
      AtomicReference<String> path,
      AtomicReference<String> authorization,
      AtomicReference<String> referrer,
      AtomicReference<String> title)
      implements AutoCloseable {

    static CapturingServer start(String responseBody) throws IOException {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      AtomicReference<String> path = new AtomicReference<>();
      AtomicReference<String> authorization = new AtomicReference<>();
      AtomicReference<String> referrer = new AtomicReference<>();
      AtomicReference<String> title = new AtomicReference<>();
      byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/",
          exchange -> {
            try {
              path.set(exchange.getRequestURI().getPath());
              authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
              referrer.set(exchange.getRequestHeaders().getFirst("HTTP-Referer"));
              title.set(exchange.getRequestHeaders().getFirst("X-Title"));
              exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, responseBytes.length);
              exchange.getResponseBody().write(responseBytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      return new CapturingServer(
          server,
          "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort(),
          path,
          authorization,
          referrer,
          title);
    }

    String capturedPath() {
      return path.get();
    }

    String capturedAuthorization() {
      return authorization.get();
    }

    String capturedReferrer() {
      return referrer.get();
    }

    String capturedTitle() {
      return title.get();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
