package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import com.rft.chat_sribiddi.chat.repository.DocumentChunkRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetrievalService {

  private static final int MAX_CHUNKS = 4;

  private final DocumentChunkRepository chunkRepository;

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

  @Transactional(readOnly = true)
  public String retrieveContext(UUID chatId, String userId, String query) {
    return DocumentContextFormatter.formatDocuments(retrieveDocuments(chatId, userId, query));
  }

  private static boolean isGenericAttachmentPrompt(String query) {
    String normalized = query.toLowerCase();
    return normalized.contains("analyze the attachment")
        || normalized.contains("analysiere den anhang")
        || normalized.contains("analyze attachment");
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
