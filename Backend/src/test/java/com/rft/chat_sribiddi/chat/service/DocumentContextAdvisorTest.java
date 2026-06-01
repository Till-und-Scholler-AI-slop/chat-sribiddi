package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

class DocumentContextAdvisorTest {

  @Test
  void create_usesSpringAiRetrievalAugmentationAdvisorForDocumentContext() {
    RetrievalAugmentationAdvisor advisor =
        DocumentContextAdvisor.create(
            _ ->
                List.of(
                    new Document(
                        "Important text.",
                        Map.of(
                            "document_id", 1L,
                            "chunk_index", 0,
                            "page_start", 2,
                            "page_end", 3))),
            new DocumentContextFormatter(new RagProperties()));
    UserMessage userMessage =
        UserMessage.builder()
            .text("What is in this file?")
            .metadata(Map.of("sourceMessageId", 10L))
            .build();
    ChatClientRequest request =
        ChatClientRequest.builder()
            .prompt(new Prompt(List.of(new SystemMessage("system"), userMessage)))
            .build();

    ChatClientRequest augmentedRequest = advisor.before(request, new NoopAdvisorChain());

    List<Message> messages = augmentedRequest.prompt().getInstructions();
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
    UserMessage augmented = (UserMessage) messages.get(1);
    assertThat(augmented.getText())
        .contains("retrieved document excerpts")
        .contains("[document 1, chunk 1, pages 2-3]")
        .contains("Important text.")
        .contains("User request:")
        .contains("What is in this file?");
    assertThat(augmented.getMetadata()).containsEntry("sourceMessageId", 10L);
    assertThat(augmentedRequest.context())
        .containsKey(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
  }

  private static final class NoopAdvisorChain implements AdvisorChain {}
}
