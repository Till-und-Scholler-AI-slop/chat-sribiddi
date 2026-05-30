package com.rft.chat_sribiddi.chat.service;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

final class DocumentContextAdvisor {

  private static final PromptTemplate DOCUMENT_CONTEXT_PROMPT =
      new PromptTemplate(
          """
          Use these retrieved document excerpts as context when they are relevant. If the excerpts do not contain the answer, say so instead of inventing details.

          {context}

          User request:
          {query}
          """);

  private DocumentContextAdvisor() {}

  static RetrievalAugmentationAdvisor create(
      DocumentRetriever documentRetriever, DocumentContextFormatter documentContextFormatter) {
    return RetrievalAugmentationAdvisor.builder()
        .documentRetriever(documentRetriever)
        .queryAugmenter(
            ContextualQueryAugmenter.builder()
                .promptTemplate(DOCUMENT_CONTEXT_PROMPT)
                .allowEmptyContext(true)
                .documentFormatter(documentContextFormatter::formatDocuments)
                .build())
        .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1)
        .build();
  }
}
