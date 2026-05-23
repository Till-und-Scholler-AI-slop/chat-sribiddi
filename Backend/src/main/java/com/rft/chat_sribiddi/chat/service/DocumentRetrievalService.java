package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import com.rft.chat_sribiddi.chat.repository.DocumentChunkRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetrievalService {

  private static final int MAX_CHUNKS = 4;
  private static final int MAX_CONTEXT_CHARS = 12_000;

  private final DocumentChunkRepository chunkRepository;

  @Transactional(readOnly = true)
  public String retrieveContext(UUID chatId, String userId, String query) {
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

    if (chunks.isEmpty()) {
      return null;
    }

    StringBuilder context = new StringBuilder();
    context.append("Relevant document excerpts for this chat:\n");
    for (DocumentChunkEntity chunk : chunks) {
      String pageRange = pageRange(chunk);
      context
          .append("\n[document ")
          .append(chunk.getDocumentId())
          .append(", chunk ")
          .append(chunk.getChunkIndex() + 1);
      if (pageRange != null) {
        context.append(", pages ").append(pageRange);
      }
      context.append("]\n").append(chunk.getText().trim()).append('\n');
      if (context.length() >= MAX_CONTEXT_CHARS) {
        break;
      }
    }
    if (context.length() > MAX_CONTEXT_CHARS) {
      return context.substring(0, MAX_CONTEXT_CHARS);
    }
    return context.toString();
  }

  private static boolean isGenericAttachmentPrompt(String query) {
    String normalized = query.toLowerCase();
    return normalized.contains("analyze the attachment")
        || normalized.contains("analysiere den anhang")
        || normalized.contains("analyze attachment");
  }

  private static String pageRange(DocumentChunkEntity chunk) {
    if (chunk.getPageStart() == null) {
      return null;
    }
    if (chunk.getPageEnd() == null || chunk.getPageStart().equals(chunk.getPageEnd())) {
      return chunk.getPageStart().toString();
    }
    return chunk.getPageStart() + "-" + chunk.getPageEnd();
  }
}
