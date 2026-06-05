package com.rft.chat_sribiddi.chat.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.Timeout;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.ChatRuntimeOptions;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Primary
@Component
public class DefaultProviderClientFactory implements ProviderClientFactory {

  private final OpenAiProviderProperties openAiProperties;
  private final GoogleProviderProperties googleProperties;
  private final OpenRouterProviderProperties openRouterProperties;
  private final ObservationRegistry observationRegistry;
  private final ObjectMapper objectMapper;
  private final Cache<String, ProviderClientHandle> openAiCache;
  private final Cache<String, ProviderClientHandle> googleCache;
  private final Cache<String, ProviderClientHandle> claudeCache;
  private final Cache<String, ProviderClientHandle> openRouterCache;

  public DefaultProviderClientFactory(
      OpenAiProviderProperties openAiProperties,
      GoogleProviderProperties googleProperties,
      OpenRouterProviderProperties openRouterProperties,
      ObservationRegistry observationRegistry) {
    this.openAiProperties = openAiProperties;
    this.googleProperties = googleProperties;
    this.openRouterProperties = openRouterProperties;
    this.observationRegistry =
        observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
    this.objectMapper = new ObjectMapper();
    this.openAiCache = buildCache();
    this.googleCache = buildCache();
    this.claudeCache = buildCache();
    this.openRouterCache = buildCache();
  }

  private Cache<String, ProviderClientHandle> buildCache() {
    return Caffeine.newBuilder()
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .maximumSize(200)
        .removalListener(
            (String _, ProviderClientHandle holder, RemovalCause _) -> {
              try {
                holder.close();
              } catch (Exception e) {
                log.warn("Failed to close evicted provider client", e);
              }
            })
        .build();
  }

  @Override
  public ProviderClientHandle getClient(
      Provider provider, String apiKey, CredentialSource credentialSource) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key is required for provider %s".formatted(provider));
    }
    if (credentialSource == null) {
      throw new IllegalArgumentException("credentialSource must not be null");
    }
    String cacheKey = credentialSource.name() + ":" + hashApiKey(apiKey);

    return switch (provider) {
      case OPENAI ->
          openAiCache.get(
              cacheKey,
              _ -> {
                ChatClient client =
                    chatClient(
                        OpenAiChatModel.builder()
                            .openAiClient(buildOpenAiClient(apiKey))
                            .openAiClientAsync(buildOpenAiAsyncClient(apiKey)));
                return new ProviderClientHandle(client, null);
              });
      case GOOGLE ->
          googleCache.get(
              cacheKey,
              _ -> {
                Client googleClient =
                    Client.builder().apiKey(apiKey).httpOptions(buildGoogleHttpOptions()).build();
                GoogleGenAiChatModel model =
                    GoogleGenAiChatModel.builder()
                        .genAiClient(googleClient)
                        .observationRegistry(observationRegistry)
                        .build();
                ChatClient chatClient = ChatClient.create(model, observationRegistry);
                return new ProviderClientHandle(chatClient, null);
              });
      case CLAUDE ->
          claudeCache.get(
              cacheKey,
              _ -> {
                AnthropicClient anthropicClient =
                    AnthropicSetup.setupSyncClient(apiKey, null, null, null, null, null);
                AnthropicClientAsync anthropicClientAsync =
                    AnthropicSetup.setupAsyncClient(apiKey, null, null, null, null, null);
                ChatClient chatClient =
                    chatClient(
                        AnthropicChatModel.builder()
                            .anthropicClient(anthropicClient)
                            .anthropicClientAsync(anthropicClientAsync)
                            .observationRegistry(observationRegistry));
                return new ProviderClientHandle(chatClient, null);
              });
      case OPENROUTER ->
          openRouterCache.get(
              cacheKey,
              _ -> {
                ChatClient chatClient =
                    chatClient(
                        OpenAiChatModel.builder()
                            .openAiClient(buildOpenRouterClient(apiKey))
                            .openAiClientAsync(buildOpenRouterAsyncClient(apiKey)));
                return new ProviderClientHandle(chatClient, null);
              });
      case BRAVE_SEARCH ->
          throw new UnsupportedOperationException("BRAVE_SEARCH is not a chat model provider");
    };
  }

  @Override
  public ProviderClientHandle getClient(Provider provider, ResolvedCredential credential) {
    Objects.requireNonNull(credential, "credential must not be null");
    if (provider == Provider.GOOGLE && credential.hasOAuthTokens()) {
      if (credential.refreshToken() == null || credential.refreshToken().isBlank()) {
        throw new IllegalArgumentException("Google OAuth refresh token is required");
      }
      if (credential.clientId() == null
          || credential.clientId().isBlank()
          || credential.clientSecret() == null
          || credential.clientSecret().isBlank()) {
        throw new IllegalArgumentException("Google OAuth client credentials are required");
      }
      String cacheKey = credential.credentialSource().name() + ":" + hashApiKey(credential.cacheMaterial());
      return googleCache.get(
          cacheKey,
          _ -> {
            GoogleOAuthChatModel model =
                new GoogleOAuthChatModel(
                    credential, RestClient.builder().build(), objectMapper);
            ChatClient chatClient = ChatClient.create(model, observationRegistry);
            return new ProviderClientHandle(chatClient, null);
          });
    }
    return getClient(provider, credential.apiKey(), credential.credentialSource());
  }

  private ChatClient chatClient(OpenAiChatModel.Builder builder) {
    return ChatClient.create(
        builder.observationRegistry(observationRegistry).build(), observationRegistry);
  }

  private ChatClient chatClient(AnthropicChatModel.Builder builder) {
    return ChatClient.create(
        builder.observationRegistry(observationRegistry).build(), observationRegistry);
  }

  @Override
  public ChatOptions buildOptions(Provider provider, String modelId, String apiKey) {
    return buildOptions(provider, modelId, apiKey, null);
  }

  @Override
  public ChatOptions buildOptions(
      Provider provider, String modelId, String apiKey, ChatRuntimeOptions runtimeOptions) {
    return switch (provider) {
      case OPENAI ->
          openAiCompatibleOptions(modelId, runtimeOptions);
      case GOOGLE ->
          googleOptions(modelId, runtimeOptions);
      case CLAUDE ->
          anthropicOptions(modelId, runtimeOptions);
      case OPENROUTER ->
          openAiCompatibleOptions(modelId, runtimeOptions);
      case BRAVE_SEARCH ->
          throw new UnsupportedOperationException("BRAVE_SEARCH is not a chat model provider");
    };
  }

  private OpenAiChatOptions openAiCompatibleOptions(
      String modelId, ChatRuntimeOptions runtimeOptions) {
    OpenAiChatOptions.Builder builder =
        applyRuntimeOptions(
            OpenAiChatOptions.builder().model(modelId).streamUsage(true), runtimeOptions);
    applyOpenAiRuntimeOptions(builder, runtimeOptions);
    return builder.build();
  }

  private <B extends ChatOptions.Builder<B>> B applyRuntimeOptions(
      B builder, ChatRuntimeOptions runtimeOptions) {
    if (runtimeOptions == null) {
      return builder;
    }
    if (runtimeOptions.temperature() != null) {
      builder.temperature(runtimeOptions.temperature());
    }
    if (runtimeOptions.topP() != null) {
      builder.topP(runtimeOptions.topP());
    }
    if (runtimeOptions.topK() != null) {
      builder.topK(runtimeOptions.topK());
    }
    if (runtimeOptions.maxTokens() != null) {
      builder.maxTokens(runtimeOptions.maxTokens());
    }
    if (runtimeOptions.frequencyPenalty() != null) {
      builder.frequencyPenalty(runtimeOptions.frequencyPenalty());
    }
    if (runtimeOptions.presencePenalty() != null) {
      builder.presencePenalty(runtimeOptions.presencePenalty());
    }
    if (runtimeOptions.stopSequences() != null && !runtimeOptions.stopSequences().isEmpty()) {
      builder.stopSequences(List.copyOf(runtimeOptions.stopSequences()));
    }
    return builder;
  }

  private GoogleGenAiChatOptions googleOptions(
      String modelId, ChatRuntimeOptions runtimeOptions) {
    GoogleGenAiChatOptions.Builder builder =
        applyRuntimeOptions(GoogleGenAiChatOptions.builder().model(modelId), runtimeOptions);
    applyGoogleGenAiRuntimeOptions(builder, runtimeOptions);
    // Google client uses the apiKey bound to the Client instance; header not needed here.
    return builder.build();
  }

  private void applyGoogleGenAiRuntimeOptions(
      GoogleGenAiChatOptions.Builder builder, ChatRuntimeOptions runtimeOptions) {
    if (runtimeOptions == null) {
      return;
    }
    if (runtimeOptions.thinkingBudget() != null && runtimeOptions.thinkingLevel() != null) {
      throw new IllegalArgumentException(
          "thinkingBudget and thinkingLevel cannot be used together");
    }
    if (runtimeOptions.thinkingBudget() != null) {
      builder.thinkingBudget(runtimeOptions.thinkingBudget());
    }
    if (runtimeOptions.thinkingLevel() != null) {
      builder.thinkingLevel(runtimeOptions.thinkingLevel());
    }
    if (runtimeOptions.includeThoughts() != null) {
      builder.includeThoughts(runtimeOptions.includeThoughts());
    }
    if (runtimeOptions.googleSearchRetrieval() != null) {
      builder.googleSearchRetrieval(runtimeOptions.googleSearchRetrieval());
    }
    if (runtimeOptions.includeServerSideToolInvocations() != null) {
      builder.includeServerSideToolInvocations(
          runtimeOptions.includeServerSideToolInvocations());
    }
    if (runtimeOptions.includeExtendedUsageMetadata() != null) {
      builder.includeExtendedUsageMetadata(runtimeOptions.includeExtendedUsageMetadata());
    }
  }

  private void applyOpenAiRuntimeOptions(
      OpenAiChatOptions.Builder builder, ChatRuntimeOptions runtimeOptions) {
    if (runtimeOptions == null) {
      return;
    }
    if (runtimeOptions.openAiReasoningEffort() != null
        && !runtimeOptions.openAiReasoningEffort().isBlank()) {
      builder.reasoningEffort(runtimeOptions.openAiReasoningEffort().trim());
    }
    if (runtimeOptions.openAiVerbosity() != null && !runtimeOptions.openAiVerbosity().isBlank()) {
      builder.verbosity(runtimeOptions.openAiVerbosity().trim());
    }
    if (runtimeOptions.openAiServiceTier() != null
        && !runtimeOptions.openAiServiceTier().isBlank()) {
      builder.serviceTier(runtimeOptions.openAiServiceTier().trim());
    }
  }

  private AnthropicChatOptions anthropicOptions(
      String modelId, ChatRuntimeOptions runtimeOptions) {
    AnthropicChatOptions.Builder builder =
        applyRuntimeOptions(AnthropicChatOptions.builder().model(modelId), runtimeOptions);
    applyAnthropicRuntimeOptions(builder, runtimeOptions);
    return builder.build();
  }

  private void applyAnthropicRuntimeOptions(
      AnthropicChatOptions.Builder builder, ChatRuntimeOptions runtimeOptions) {
    if (runtimeOptions == null || runtimeOptions.thinkingBudget() == null) {
      return;
    }
    if (Boolean.TRUE.equals(runtimeOptions.includeThoughts())) {
      builder.thinkingEnabled(
          runtimeOptions.thinkingBudget(),
          com.anthropic.models.messages.ThinkingConfigEnabled.Display.SUMMARIZED);
      return;
    }
    if (Boolean.FALSE.equals(runtimeOptions.includeThoughts())) {
      builder.thinkingEnabled(
          runtimeOptions.thinkingBudget(),
          com.anthropic.models.messages.ThinkingConfigEnabled.Display.OMITTED);
      return;
    }
    builder.thinkingEnabled(runtimeOptions.thinkingBudget());
  }

  HttpOptions buildGoogleHttpOptions() {
    return HttpOptions.builder()
        .timeout(Math.toIntExact(googleProperties.getTimeout().toMillis()))
        .build();
  }

  private OpenAIClient buildOpenAiClient(String apiKey) {
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(buildOpenAiTimeout())
        .build();
  }

  private OpenAIClientAsync buildOpenAiAsyncClient(String apiKey) {
    return OpenAIOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .timeout(buildOpenAiTimeout())
        .build();
  }

  private OpenAIClient buildOpenRouterClient(String apiKey) {
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(openRouterProperties.getBaseUrl())
        .headers(buildOpenRouterAttributionHeaderMap())
        .timeout(buildOpenAiTimeout())
        .build();
  }

  private OpenAIClientAsync buildOpenRouterAsyncClient(String apiKey) {
    return OpenAIOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .baseUrl(openRouterProperties.getBaseUrl())
        .headers(buildOpenRouterAttributionHeaderMap())
        .timeout(buildOpenAiTimeout())
        .build();
  }

  private Timeout buildOpenAiTimeout() {
    return Timeout.builder()
        .connect(openAiProperties.getConnectTimeout())
        .read(openAiProperties.getReadTimeout())
        .request(openAiProperties.getReadTimeout())
        .build();
  }

  /**
   * Builds the OpenRouter attribution headers ({@code HTTP-Referer}, {@code X-Title}) from the
   * configured {@link OpenRouterProviderProperties}. Blank values are omitted so the resulting
   * {@link HttpHeaders} is always safe to attach to the OpenAI SDK client builder.
   */
  HttpHeaders buildOpenRouterAttributionHeaders() {
    HttpHeaders headers = new HttpHeaders();
    String referrer = openRouterProperties.getReferrer();
    if (referrer != null && !referrer.isBlank()) {
      headers.set("HTTP-Referer", referrer);
    }
    String appName = openRouterProperties.getAppName();
    if (appName != null && !appName.isBlank()) {
      headers.set("X-Title", appName);
    }
    return headers;
  }

  private Map<String, List<String>> buildOpenRouterAttributionHeaderMap() {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    buildOpenRouterAttributionHeaders()
        .forEach((name, values) -> headers.put(name, List.copyOf(values)));
    return headers;
  }

  private static String hashApiKey(String apiKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
