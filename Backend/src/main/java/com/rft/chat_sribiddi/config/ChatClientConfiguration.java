package com.rft.chat_sribiddi.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfiguration {

  @Bean
  @ConditionalOnBean(OpenAiChatModel.class)
  public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
    return ChatClient.create(openAiChatModel);
  }

  @Bean
  @ConditionalOnBean(GoogleGenAiChatModel.class)
  public ChatClient googleAiChatClient(GoogleGenAiChatModel googleGenAiChatModel) {
    return ChatClient.create(googleGenAiChatModel);
  }
}
