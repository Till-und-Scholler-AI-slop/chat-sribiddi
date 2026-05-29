package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

final class PromptAdvisors {

  private static final String CONVERSATION_ID = "chat-sribiddi-request";

  private PromptAdvisors() {}

  static void bind(
      ChatClient.ChatClientRequestSpec promptSpec,
      PromptMessageBatch promptBatch,
      DocumentRetrievalService documentRetrievalService) {
    if (promptBatch.chatMemory() != null && promptBatch.conversationId() != null) {
      promptSpec.advisors(
          advisorSpec ->
              advisorSpec
                  .param(ChatMemory.CONVERSATION_ID, promptBatch.conversationId())
                  .advisors(MessageChatMemoryAdvisor.builder(promptBatch.chatMemory()).build()));
    } else if (!promptBatch.historyMessages().isEmpty()) {
      promptSpec.advisors(
          advisorSpec ->
              advisorSpec
                  .param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID)
                  .advisors(MessageChatMemoryAdvisor.builder(chatMemory(promptBatch)).build()));
    }
    if (promptBatch.hasDocumentContext() && documentRetrievalService != null) {
      PromptMessageBuilder.DocumentRetrievalContext context =
          promptBatch.documentRetrievalContext();
      promptSpec.advisors(
          advisorSpec ->
              advisorSpec
                  .param(DocumentRetrievalService.CHAT_ID_CONTEXT_KEY, context.chatId())
                  .param(DocumentRetrievalService.USER_ID_CONTEXT_KEY, context.userId())
                  .advisors(DocumentContextAdvisor.create(documentRetrievalService)));
    }
  }

  private static ChatMemory chatMemory(PromptMessageBatch promptBatch) {
    List<Message> historyMessages = promptBatch.historyMessages();
    ChatMemory chatMemory =
        MessageWindowChatMemory.builder()
            .maxMessages(Math.max(1, promptBatch.totalMessageCount()))
            .build();
    chatMemory.add(CONVERSATION_ID, historyMessages);
    return chatMemory;
  }
}
