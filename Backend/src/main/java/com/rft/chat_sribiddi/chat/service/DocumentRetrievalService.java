package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import com.rft.chat_sribiddi.chat.repository.DocumentChunkRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetrievalService implements DocumentRetriever {

  static final String CHAT_ID_CONTEXT_KEY = "chat-sribiddi.chatId";
  static final String USER_ID_CONTEXT_KEY = "chat-sribiddi.userId";
  private static final int MAX_CHUNKS = 4;

  private final DocumentChunkRepository chunkRepository;

  @Override
  @Transactional(readOnly = true)
  public List<Document> retrieve(Query query) {
    if (query == null) {
      return List.of();
    }
    Map<String, Object> context = query.context() == null ? Map.of() : query.context();
    UUID chatId = parseChatId(context.get(CHAT_ID_CONTEXT_KEY));
    String userId = parseUserId(context.get(USER_ID_CONTEXT_KEY));
    if (chatId == null || userId == null) {
      return List.of();
    }
    return retrieveDocuments(chatId, userId, query.text());
  }

  @Transactional(readOnly = true)
  public List<Document> retrieveDocuments(UUID chatId, String userId, String query) {
    List<DocumentChunkEntity> chunks;
    String normalizedQuery = query == null ? "" : query.trim();
    if (normalizedQuery.isBlank() || isGenericAttachmentPrompt(normalizedQuery)) {
      chunks = chunkRepository.findInitialChunks(chatId, userId, PageRequest.of(0, MAX_CHUNKS));
    } else {
      chunks = chunkRepository.search(chatId, userId, normalizedQuery, PageRequest.of(0, MAX_CHUNKS));
      if (chunks.isEmpty()) {
        chunks = chunkRepository.findInitialChunks(chatId, userId, PageRequest.of(0, MAX_CHUNKS));
      }
    }

    return chunks.stream()
        .filter(chunk -> chunk.getText() != null && !chunk.getText().isBlank())
        .map(DocumentRetrievalService::toDocument)
        .toList();
  }

  private static boolean isGenericAttachmentPrompt(String query) {
    String normalized = query.toLowerCase();
    return normalized.contains("analyze the attachment")
        || normalized.contains("analysiere den anhang")
        || normalized.contains("analyze attachment");
  }

  private static UUID parseChatId(Object value) {
    if (value instanceof UUID uuid) {
      return uuid;
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String parseUserId(Object value) {
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    return null;
  }

  private static Document toDocument(DocumentChunkEntity chunk) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("document_id", chunk.getDocumentId());
    metadata.put("chunk_index", chunk.getChunkIndex());
    if (chunk.getPageStart() != null) {
      metadata.put("page_start", chunk.getPageStart());
    }
    if (chunk.getPageEnd() != null) {
      metadata.put("page_end", chunk.getPageEnd());
    }
    return new Document(chunk.getText().trim(), metadata);
  }
}
