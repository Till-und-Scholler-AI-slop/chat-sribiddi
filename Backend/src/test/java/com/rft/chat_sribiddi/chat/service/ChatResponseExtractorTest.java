package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatResponseExtractorTest {

  @Test
  void extractsTextUsageAndReasoningFromSpringAiChatResponse() {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("visible answer")
            .properties(Map.of("reasoningContent", "private reasoning"))
            .build();
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 7)).build();
    ChatResponse response = new ChatResponse(List.of(new Generation(message)), metadata);

    assertThat(ChatResponseExtractor.text(response)).isEqualTo("visible answer");
    assertThat(ChatResponseExtractor.reasoningContent(response)).isEqualTo("private reasoning");
    assertThat(ChatResponseExtractor.usage(response))
        .hasValueSatisfying(
            usage -> {
              assertThat(usage.getPromptTokens()).isEqualTo(12);
              assertThat(usage.getCompletionTokens()).isEqualTo(7);
              assertThat(usage.getTotalTokens()).isEqualTo(19);
            });
  }

  @Test
  void utf8LengthCountsSupplementaryCharactersAsFourBytes() {
    assertThat(ChatResponseExtractor.utf8Length("aä😀")).isEqualTo(7);
  }

  @Test
  void callPrefersResponseEntityContentWhenAvailable() {
    ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    when(promptSpec.call()).thenReturn(callResponseSpec);
    ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("fallback"))));
    when(callResponseSpec.responseEntity(String.class))
        .thenReturn(new ResponseEntity<>(response, "entity text"));

    ChatResponseExtractor.ChatCallResult result = ChatResponseExtractor.call(promptSpec);

    assertThat(result.response()).isEqualTo(response);
    assertThat(result.content()).isEqualTo("entity text");
  }
}
