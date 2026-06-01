package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

class PromptAdvisorsTest {

  @Test
  void bind_usesSpringAiMessageChatMemoryAdvisorForHistory() {
    ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
    PromptMessageBuilder.PromptMessageBatch batch =
        new PromptMessageBuilder.PromptMessageBatch(
            List.of(new UserMessage("current")),
            List.of(new UserMessage("previous")),
            null,
            null,
            null,
            2);

    PromptAdvisors.bind(promptSpec, batch, null, new DocumentContextFormatter(new RagProperties()));

    ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).advisors(captor.capture());
    CapturingAdvisorSpec advisorSpec = new CapturingAdvisorSpec();
    captor.getValue().accept(advisorSpec);

    assertThat(advisorSpec.params())
        .containsEntry(ChatMemory.CONVERSATION_ID, "chat-sribiddi-request");
    assertThat(advisorSpec.advisors()).singleElement().isInstanceOf(MessageChatMemoryAdvisor.class);
  }

  @Test
  void bind_usesProvidedSpringAiChatMemoryForPersistentHistory() {
    ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
    org.springframework.ai.chat.memory.ChatMemory chatMemory =
        mock(org.springframework.ai.chat.memory.ChatMemory.class);
    PromptMessageBuilder.PromptMessageBatch batch =
        new PromptMessageBuilder.PromptMessageBatch(
            List.of(new UserMessage("current")),
            List.of(),
            chatMemory,
            "conversation-1",
            null,
            2);

    PromptAdvisors.bind(promptSpec, batch, null, new DocumentContextFormatter(new RagProperties()));

    ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).advisors(captor.capture());
    CapturingAdvisorSpec advisorSpec = new CapturingAdvisorSpec();
    captor.getValue().accept(advisorSpec);

    assertThat(advisorSpec.params())
        .containsEntry(ChatMemory.CONVERSATION_ID, "conversation-1");
    assertThat(advisorSpec.advisors()).singleElement().isInstanceOf(MessageChatMemoryAdvisor.class);
  }

  @Test
  void bind_usesDocumentContextAdvisorForRetrievedContext() {
    ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
    DocumentRetrievalService documentRetrievalService = mock(DocumentRetrievalService.class);
    PromptMessageBuilder.DocumentRetrievalContext context =
        new PromptMessageBuilder.DocumentRetrievalContext(
            java.util.UUID.randomUUID(), "user-1");
    PromptMessageBuilder.PromptMessageBatch batch =
        new PromptMessageBuilder.PromptMessageBatch(
            List.of(new UserMessage("current")),
            List.of(),
            null,
            null,
            context,
            1);

    PromptAdvisors.bind(promptSpec, batch, documentRetrievalService, new DocumentContextFormatter(new RagProperties()));

    ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).advisors(captor.capture());
    CapturingAdvisorSpec advisorSpec = new CapturingAdvisorSpec();
    captor.getValue().accept(advisorSpec);

    assertThat(advisorSpec.params())
        .containsEntry(DocumentRetrievalService.CHAT_ID_CONTEXT_KEY, context.chatId())
        .containsEntry(DocumentRetrievalService.USER_ID_CONTEXT_KEY, "user-1");
    assertThat(advisorSpec.advisors())
        .singleElement()
        .isInstanceOf(RetrievalAugmentationAdvisor.class);
  }

  private static final class CapturingAdvisorSpec implements ChatClient.AdvisorSpec {
    private final List<Advisor> advisors = new ArrayList<>();
    private final java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();

    @Override
    public ChatClient.AdvisorSpec param(String key, Object value) {
      params.put(key, value);
      return this;
    }

    @Override
    public ChatClient.AdvisorSpec params(Map<String, Object> params) {
      this.params.putAll(params);
      return this;
    }

    @Override
    public ChatClient.AdvisorSpec advisors(Advisor... advisors) {
      this.advisors.addAll(List.of(advisors));
      return this;
    }

    @Override
    public ChatClient.AdvisorSpec advisors(List<Advisor> advisors) {
      this.advisors.addAll(advisors);
      return this;
    }

    private List<Advisor> advisors() {
      return advisors;
    }

    private Map<String, Object> params() {
      return params;
    }
  }
}
