package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;

class DocumentTextExtractorTest {

  private final DocumentTextExtractor extractor = new DocumentTextExtractor();

  @Test
  void extract_usesSpringAiTextReaderForTextDocuments() {
    var pages =
        extractor.extract(
            "text/plain; charset=utf-8",
            "line one\r\nline two\n".getBytes(StandardCharsets.UTF_8));

    assertThat(pages).hasSize(1);
    assertThat(pages.getFirst().page()).isNull();
    assertThat(pages.getFirst().text()).isEqualTo("line one\nline two");
  }

  @Test
  void extract_usesSpringAiPagePdfDocumentReaderForPdfDocuments() {
    assertThat(PagePdfDocumentReader.class).isNotNull();
    byte[] pdf =
        """
        %PDF-1.4
        1 0 obj
        << /Type /Catalog /Pages 2 0 R >>
        endobj
        2 0 obj
        << /Type /Pages /Kids [3 0 R] /Count 1 >>
        endobj
        3 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
        endobj
        4 0 obj
        << /Length 73 >>
        stream
        BT
        /F1 24 Tf
        100 700 Td
        (Spring AI PDF reader text) Tj
        ET
        endstream
        endobj
        5 0 obj
        << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
        endobj
        xref
        0 6
        0000000000 65535 f\s
        0000000009 00000 n\s
        0000000058 00000 n\s
        0000000115 00000 n\s
        0000000241 00000 n\s
        0000000364 00000 n\s
        trailer
        << /Size 6 /Root 1 0 R >>
        startxref
        434
        %%EOF
        """
            .getBytes(StandardCharsets.US_ASCII);

    var pages = extractor.extract("application/pdf", pdf);

    assertThat(pages).hasSize(1);
    assertThat(pages.getFirst().page()).isEqualTo(1);
    assertThat(pages.getFirst().text()).contains("Spring AI PDF reader text");
  }

  @Test
  void supports_acceptsStructuredTextMimeTypes() {
    assertThat(extractor.supports("application/json")).isTrue();
    assertThat(extractor.supports("application/xml")).isTrue();
    assertThat(extractor.supports("application/octet-stream")).isFalse();
  }

  @Test
  void extract_rejectsUnsupportedMimeTypes() {
    assertThatThrownBy(
            () -> extractor.extract("application/octet-stream", "data".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported document type");
  }
}
