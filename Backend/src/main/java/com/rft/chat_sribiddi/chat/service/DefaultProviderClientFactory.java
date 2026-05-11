package com.rft.chat_sribiddi.chat.service;

import com.anthropic.client.AnthropicClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.net.http.HttpClient;
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
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Primary
@Component
public class DefaultProviderClientFactory implements ProviderClientFactory {

  private final OpenAiProviderProperties openAiProperties;
  private final GoogleProviderProperties googleProperties;
  private final OpenRouterProviderProperties openRouterProperties;
  private final Cache<String, ProviderClientHandle> openAiCache;
  private final Cache<String, ProviderClientHandle> googleCache;
  private final Cache<String, ProviderClientHandle> claudeCache;
  private final Cache<String, ProviderClientHandle> openRouterCache;

  public DefaultProviderClientFactory(
      OpenAiProviderProperties openAiProperties,
      GoogleProviderProperties googleProperties,
      OpenRouterProviderProperties openRouterProperties) {
    this.openAiProperties = openAiProperties;
    this.googleProperties = googleProperties;
    this.openRouterProperties = openRouterProperties;
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
      Provider provider, String apiKey, BillingMode billingMode) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key is required for provider %s".formatted(provider));
    }
    if (billingMode == null) {
      throw new IllegalArgumentException("billingMode must not be null");
    }
    // Prefix the cache key with the billing mode so that user-supplied (BYOK) keys and
    // server-managed (MANAGED) keys never share cache entries, even if their raw values collide.
    String cacheKey = billingMode.name() + ":" + apiKey;

    return switch (provider) {
      case OPENAI ->
          openAiCache.get(
              cacheKey,
              _ -> {
                HttpClient httpClient =
                    HttpClient.newBuilder()
                        .connectTimeout(openAiProperties.getConnectTimeout())
                        .build();
                JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(httpClient);
                requestFactory.setReadTimeout(openAiProperties.getReadTimeout());

                OpenAiApi openAiApi =
                    OpenAiApi.builder()
                        .apiKey(apiKey)
                        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                        .build();
                ChatClient client =
                    ChatClient.create(OpenAiChatModel.builder().openAiApi(openAiApi).build());
                return new ProviderClientHandle(client, null);
              });
      case GOOGLE ->
          googleCache.get(
              cacheKey,
              _ -> {
                Client googleClient =
                    Client.builder().apiKey(apiKey).httpOptions(buildGoogleHttpOptions()).build();
                GoogleGenAiChatModel model =
                    GoogleGenAiChatModel.builder().genAiClient(googleClient).build();
                ChatClient chatClient = ChatClient.create(model);
                return new ProviderClientHandle(chatClient, null);
              });
      case CLAUDE ->
          claudeCache.get(
              cacheKey,
              _ -> {
                AnthropicClient anthropicClient =
                    AnthropicSetup.setupSyncClient(apiKey, null, null, null, null, null);
                ChatClient chatClient =
                    ChatClient.create(
                        AnthropicChatModel.builder().anthropicClient(anthropicClient).build());
                return new ProviderClientHandle(chatClient, null);
              });
      case OPENROUTER ->
          openRouterCache.get(
              cacheKey,
              _ -> {
                HttpClient httpClient =
                    HttpClient.newBuilder()
                        .connectTimeout(openAiProperties.getConnectTimeout())
                        .build();
                JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(httpClient);
                requestFactory.setReadTimeout(openAiProperties.getReadTimeout());

                OpenAiApi openRouterApi =
                    OpenAiApi.builder()
                        .apiKey(apiKey)
                        .baseUrl(openRouterProperties.getBaseUrl())
                        .completionsPath(openRouterProperties.getCompletionsPath())
                        .headers(buildOpenRouterAttributionHeaders())
                        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                        .build();
                ChatClient chatClient =
                    ChatClient.create(OpenAiChatModel.builder().openAiApi(openRouterApi).build());
                return new ProviderClientHandle(chatClient, null);
              });
      case BRAVE_SEARCH ->
          throw new UnsupportedOperationException("BRAVE_SEARCH is not a chat model provider");
    };
  }

  @Override
  public ChatOptions buildOptions(Provider provider, String modelId, String apiKey) {
    return switch (provider) {
      case OPENAI -> OpenAiChatOptions.builder().model(modelId).build();
      case GOOGLE ->
          GoogleGenAiChatOptions.builder()
              .model(modelId)
              // Google client uses the apiKey bound to the Client instance; header not needed here.
              .build();
      case CLAUDE -> AnthropicChatOptions.builder().model(modelId).build();
      case OPENROUTER -> OpenAiChatOptions.builder().model(modelId).build();
      case BRAVE_SEARCH ->
          throw new UnsupportedOperationException("BRAVE_SEARCH is not a chat model provider");
    };
  }

  HttpOptions buildGoogleHttpOptions() {
    return HttpOptions.builder()
        .timeout(Math.toIntExact(googleProperties.getTimeout().toMillis()))
        .build();
  }

  /**
   * Builds the OpenRouter attribution headers ({@code HTTP-Referer}, {@code X-Title}) from the
   * configured {@link OpenRouterProviderProperties}. Blank values are omitted so the resulting
   * {@link HttpHeaders} is always safe to attach to the {@link OpenAiApi} builder.
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
}
