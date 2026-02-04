package com.rft.chat_sribiddi.chat.service;

import com.google.genai.Client;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Primary
@Component
public class DefaultProviderClientFactory implements ProviderClientFactory {

  private final Map<String, ChatService.ClientHolder> openAiCache = new ConcurrentHashMap<>();
  private final Map<String, ChatService.ClientHolder> googleCache = new ConcurrentHashMap<>();

  @Override
  public ChatService.ClientHolder getClient(Provider provider, String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key is required for provider %s".formatted(provider));
    }

    return switch (provider) {
      case OPENAI -> {
        yield openAiCache.computeIfAbsent(
            apiKey,
            key -> {
              OpenAiApi openAiApi = OpenAiApi.builder().apiKey(key).build();
              ChatClient client =
                  ChatClient.create(OpenAiChatModel.builder().openAiApi(openAiApi).build());
              return new ChatService.ClientHolder(client, null);
            });
      }
      case GOOGLE -> {
        yield googleCache.computeIfAbsent(
            apiKey,
            key -> {
              Client googleClient = Client.builder().apiKey(key).build();
              GoogleGenAiChatModel model =
                  GoogleGenAiChatModel.builder().genAiClient(googleClient).build();
              ChatClient chatClient = ChatClient.create(model);
              return new ChatService.ClientHolder(chatClient, googleClient);
            });
      }
    };
  }

  @Override
  public ChatOptions buildOptions(Provider provider, String modelId, String apiKey) {
    return switch (provider) {
      case OPENAI ->
          OpenAiChatOptions.builder()
              .model(modelId)
              .httpHeaders(Map.of("Authorization", "Bearer " + apiKey))
              .build();
      case GOOGLE ->
          GoogleGenAiChatOptions.builder()
              .model(modelId)
              // Google client uses the apiKey bound to the Client instance; header not needed here.
              .build();
    };
  }
}
