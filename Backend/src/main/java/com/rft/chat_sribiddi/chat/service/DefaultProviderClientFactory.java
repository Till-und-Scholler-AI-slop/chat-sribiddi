package com.rft.chat_sribiddi.chat.service;

import com.google.genai.Client;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@org.springframework.context.annotation.Primary
@Component
public class DefaultProviderClientFactory implements ProviderClientFactory {

  private final OpenAiProviderProperties openAiProperties;
  private final Map<String, ChatService.ClientHolder> openAiCache = new ConcurrentHashMap<>();
  private final Map<String, ChatService.ClientHolder> googleCache = new ConcurrentHashMap<>();
  private final Map<String, ChatService.ClientHolder> claudeCache = new ConcurrentHashMap<>();

  public DefaultProviderClientFactory(OpenAiProviderProperties openAiProperties) {
    this.openAiProperties = openAiProperties;
  }

  @Override
  public ChatService.ClientHolder getClient(Provider provider, String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key is required for provider %s".formatted(provider));
    }

    return switch (provider) {
      case OPENAI ->
          openAiCache.computeIfAbsent(
              apiKey,
              key -> {
                HttpClient httpClient =
                    HttpClient.newBuilder().connectTimeout(openAiProperties.getConnectTimeout()).build();
                JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
                requestFactory.setReadTimeout(openAiProperties.getReadTimeout());

                OpenAiApi openAiApi =
                    OpenAiApi.builder()
                        .apiKey(key)
                        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                        .build();
                ChatClient client =
                    ChatClient.create(OpenAiChatModel.builder().openAiApi(openAiApi).build());
                return new ChatService.ClientHolder(client, null);
              });
      case GOOGLE ->
          googleCache.computeIfAbsent(
              apiKey,
              key -> {
                Client googleClient = Client.builder().apiKey(key).build();
                GoogleGenAiChatModel model =
                    GoogleGenAiChatModel.builder().genAiClient(googleClient).build();
                ChatClient chatClient = ChatClient.create(model);
                return new ChatService.ClientHolder(chatClient, googleClient);
              });
      case CLAUDE ->
          claudeCache.computeIfAbsent(
              apiKey,
              key -> {
                AnthropicApi anthropicApi = AnthropicApi.builder().apiKey(key).build();
                ChatClient chatClient =
                    ChatClient.create(AnthropicChatModel.builder().anthropicApi(anthropicApi).build());
                return new ChatService.ClientHolder(chatClient, null);
              });
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
    };
  }
}
