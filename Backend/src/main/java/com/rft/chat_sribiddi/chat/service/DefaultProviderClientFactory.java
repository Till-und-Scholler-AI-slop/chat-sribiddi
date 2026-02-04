package com.rft.chat_sribiddi.chat.service;

import com.google.genai.Client;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class DefaultProviderClientFactory implements ProviderClientFactory {

  @Override
  public ChatService.ClientHolder create(Provider provider, String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key is required for provider %s".formatted(provider));
    }

    return switch (provider) {
      case OPENAI -> {
        OpenAiApi openAiApi = OpenAiApi.builder().apiKey(apiKey).build();
        ChatClient client =
            ChatClient.create(OpenAiChatModel.builder().openAiApi(openAiApi).build());
        yield new ChatService.ClientHolder(client, null);
      }
      case GOOGLE -> {
        Client googleClient = Client.builder().apiKey(apiKey).build();
        GoogleGenAiChatModel model =
            GoogleGenAiChatModel.builder().genAiClient(googleClient).build();
        ChatClient chatClient = ChatClient.create(model);
        yield new ChatService.ClientHolder(chatClient, googleClient);
      }
    };
  }
}
