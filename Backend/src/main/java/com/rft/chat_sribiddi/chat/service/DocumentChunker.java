package com.rft.chat_sribiddi.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker implements DocumentTransformer {

  private static final int TARGET_TOKENS = 1_200;
  private static final int CHARS_PER_TOKEN = 4;
  static final String CHUNK_INDEX_METADATA = "chunk_index";
  static final String PAGE_START_METADATA = "page_start";
  static final String PAGE_END_METADATA = "page_end";
  static final String TOKEN_ESTIMATE_METADATA = "token_estimate";

  private final TokenTextSplitter splitter =
      TokenTextSplitter.builder()
          .withChunkSize(TARGET_TOKENS)
          .withMinChunkSizeChars(350)
          .withMinChunkLengthToEmbed(5)
          .withMaxNumChunks(10_000)
          .withKeepSeparator(true)
          .withPunctuationMarks(List.of('.', '?', '!', '\n', ';', ':'))
          .build();

  public List<DocumentChunk> chunk(List<Document> documents) {
    return apply(documents).stream().map(DocumentChunker::toChunk).toList();
  }

  @Override
  public List<Document> apply(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return List.of();
    }

    List<Document> chunks = new ArrayList<>();
    List<Document> sourceDocuments =
        documents.stream()
            .filter(
                document ->
                    document != null
                        && document.getText() != null
                        && !document.getText().isBlank())
            .toList();
    for (Document document : splitter.apply(sourceDocuments)) {
      String text = document.getText() == null ? "" : document.getText().trim();
      if (!text.isBlank()) {
        Integer page = page(document);
        chunks.add(toChunkDocument(chunks.size(), page, text));
      }
    }
    return chunks;
  }

  private static Document toChunkDocument(int index, Integer page, String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(CHUNK_INDEX_METADATA, index);
    if (page != null) {
      metadata.put(PAGE_START_METADATA, page);
      metadata.put(PAGE_END_METADATA, page);
    }
    metadata.put(TOKEN_ESTIMATE_METADATA, estimateTokens(text));
    return new Document(text, metadata);
  }

  private static DocumentChunk toChunk(Document document) {
    return new DocumentChunk(
        integerMetadata(document, CHUNK_INDEX_METADATA, 0),
        integerMetadata(document, PAGE_START_METADATA, null),
        integerMetadata(document, PAGE_END_METADATA, null),
        document.getText(),
        integerMetadata(document, TOKEN_ESTIMATE_METADATA, estimateTokens(document.getText())));
  }

  private static Integer page(Document document) {
    return DocumentTextExtractor.pageMetadata(document);
  }

  static Integer integerMetadata(Document document, String key, Integer fallback) {
    Object value = document.getMetadata().get(key);
    return value instanceof Number number ? number.intValue() : fallback;
  }

  static int estimateTokens(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    return Math.max(1, (int) Math.ceil(text.trim().length() / (double) CHARS_PER_TOKEN));
  }

  public record DocumentChunk(
      int index, Integer pageStart, Integer pageEnd, String text, int tokenEstimate) {}
}
