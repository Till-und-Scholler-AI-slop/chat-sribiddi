package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

class DocumentChunkerTest {

  private final DocumentChunker chunker = new DocumentChunker();

  @Test
  void chunk_returnsSingleChunkForSmallDocument() {
    var chunks = chunker.chunk(List.of(pageDocument(1, "Short document text.")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().pageStart()).isEqualTo(1);
    assertThat(chunks.getFirst().pageEnd()).isEqualTo(1);
    assertThat(chunks.getFirst().text()).isEqualTo("Short document text.");
    assertThat(chunks.getFirst().tokenEstimate()).isPositive();
  }

  @Test
  void chunk_skipsBlankPages() {
    var chunks = chunker.chunk(List.of(pageDocument(1, "   "), pageDocument(2, "Useful text")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().pageStart()).isEqualTo(2);
    assertThat(chunks.getFirst().pageEnd()).isEqualTo(2);
    assertThat(chunks.getFirst().text()).isEqualTo("Useful text");
  }

  @Test
  void chunk_usesSpringAiTokenSplitterForLargePages() {
    String largeText = "This is a sentence about Spring AI document splitting. ".repeat(2_000);

    var chunks = chunker.chunk(List.of(pageDocument(3, largeText)));

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.pageStart()).isEqualTo(3);
              assertThat(chunk.pageEnd()).isEqualTo(3);
              assertThat(chunk.text()).isNotBlank();
              assertThat(chunk.tokenEstimate()).isPositive();
            });
    assertThat(chunks).extracting(DocumentChunker.DocumentChunk::index)
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
  }

  @Test
  void apply_keepsChunkMetadataOnSpringAiDocuments() {
    var chunks = chunker.apply(List.of(pageDocument(4, "Document text.")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).isEqualTo("Document text.");
    assertThat(chunks.getFirst().getMetadata())
        .containsEntry(DocumentChunker.CHUNK_INDEX_METADATA, 0)
        .containsEntry(DocumentChunker.PAGE_START_METADATA, 4)
        .containsEntry(DocumentChunker.PAGE_END_METADATA, 4);
    assertThat(chunks.getFirst().getMetadata().get(DocumentChunker.TOKEN_ESTIMATE_METADATA))
        .isInstanceOf(Integer.class);
  }

  @Test
  void chunker_isSpringAiDocumentTransformer() {
    assertThat(chunker).isInstanceOf(DocumentTransformer.class);

    var chunks = chunker.apply(List.of(pageDocument(7, "Transformer input")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).isEqualTo("Transformer input");
    assertThat(chunks.getFirst().getMetadata())
        .containsEntry(DocumentChunker.PAGE_START_METADATA, 7)
        .containsEntry(DocumentChunker.PAGE_END_METADATA, 7);
  }

  private static Document pageDocument(int page, String text) {
    return new Document(text, Map.of(DocumentTextExtractor.PAGE_METADATA, page));
  }
}
