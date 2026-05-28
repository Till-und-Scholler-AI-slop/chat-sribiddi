package com.rft.chat_sribiddi.chat.service;

import java.util.List;
import org.springframework.ai.document.Document;

final class DocumentContextFormatter {

  private static final int MAX_CONTEXT_CHARS = 12_000;
  private static final String DOCUMENT_CONTEXT_PREFIX =
      "Use these retrieved document excerpts as context when they are relevant. If the excerpts do not contain the answer, say so instead of inventing details.\n\n";

  private DocumentContextFormatter() {}

  static String withDocumentContext(String text, List<Document> documents) {
    String context = formatDocuments(documents);
    if (context == null) {
      return text;
    }
    return DOCUMENT_CONTEXT_PREFIX + context + "\n\nUser request:\n" + text;
  }

  static String formatDocuments(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return null;
    }

    StringBuilder context = new StringBuilder("Relevant document excerpts for this chat:\n");
    for (Document document : documents) {
      if (document == null || document.getText() == null || document.getText().isBlank()) {
        continue;
      }
      context
          .append('\n')
          .append(label(document))
          .append('\n')
          .append(document.getText().trim())
          .append('\n');
      if (context.length() >= MAX_CONTEXT_CHARS) {
        break;
      }
    }
    if (context.toString().equals("Relevant document excerpts for this chat:\n")) {
      return null;
    }
    if (context.length() > MAX_CONTEXT_CHARS) {
      return context.substring(0, MAX_CONTEXT_CHARS);
    }
    return context.toString();
  }

  private static String label(Document document) {
    StringBuilder label = new StringBuilder("[document ");
    label.append(metadata(document, "document_id", "unknown"));
    Object chunkIndex = metadata(document, "chunk_index", null);
    if (chunkIndex instanceof Number number) {
      label.append(", chunk ").append(number.intValue() + 1);
    }
    String pageRange = pageRange(document);
    if (pageRange != null) {
      label.append(", pages ").append(pageRange);
    }
    return label.append(']').toString();
  }

  private static String pageRange(Document document) {
    Object start = metadata(document, "page_start", null);
    if (start == null) {
      return null;
    }
    Object end = metadata(document, "page_end", null);
    if (end == null || start.equals(end)) {
      return start.toString();
    }
    return start + "-" + end;
  }

  private static Object metadata(Document document, String key, Object fallback) {
    Object value = document.getMetadata().get(key);
    return value == null ? fallback : value;
  }
}
