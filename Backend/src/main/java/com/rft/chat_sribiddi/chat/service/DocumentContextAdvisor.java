package com.rft.chat_sribiddi.chat.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

final class DocumentContextAdvisor implements CallAdvisor, StreamAdvisor {

  private final List<Document> documents;

  DocumentContextAdvisor(List<Document> documents) {
    this.documents = documents == null ? List.of() : List.copyOf(documents);
  }

  @Override
  public String getName() {
    return "chat-sribiddi-document-context";
  }

  @Override
  public int getOrder() {
    return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    return chain.nextCall(augment(request));
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
    return chain.nextStream(augment(request));
  }

  private ChatClientRequest augment(ChatClientRequest request) {
    if (documents.isEmpty()) {
      return request;
    }
    List<Message> messages = request.prompt().getInstructions();
    int userIndex = lastUserMessageIndex(messages);
    if (userIndex < 0) {
      return request;
    }

    UserMessage userMessage = (UserMessage) messages.get(userIndex);
    List<Message> augmented = new ArrayList<>(messages);
    augmented.set(
        userIndex,
        UserMessage.builder()
            .text(DocumentContextFormatter.withDocumentContext(userMessage.getText(), documents))
            .media(userMessage.getMedia())
            .metadata(userMessage.getMetadata())
            .build());

    Prompt prompt = request.prompt().mutate().messages(augmented).build();
    return request.mutate().prompt(prompt).build();
  }

  private int lastUserMessageIndex(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage) {
        return i;
      }
    }
    return -1;
  }
}
