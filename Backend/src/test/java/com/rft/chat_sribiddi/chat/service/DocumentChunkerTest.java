package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.service.DocumentTextExtractor.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

  private final DocumentChunker chunker = new DocumentChunker();

  @Test
  void chunk_returnsSingleChunkForSmallDocument() {
    var chunks = chunker.chunk(List.of(new PageText(1, "Short document text.")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().pageStart()).isEqualTo(1);
    assertThat(chunks.getFirst().pageEnd()).isEqualTo(1);
    assertThat(chunks.getFirst().text()).isEqualTo("Short document text.");
    assertThat(chunks.getFirst().tokenEstimate()).isPositive();
  }

  @Test
  void chunk_skipsBlankPages() {
    var chunks = chunker.chunk(List.of(new PageText(1, "   "), new PageText(2, "Useful text")));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().pageStart()).isEqualTo(2);
    assertThat(chunks.getFirst().text()).isEqualTo("Useful text");
  }
}
