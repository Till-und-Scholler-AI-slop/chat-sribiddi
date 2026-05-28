package com.rft.chat_sribiddi.chat.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

  public List<PageText> extract(String mimeType, byte[] data) {
    String normalized = normalizeMimeType(mimeType);
    if ("application/pdf".equals(normalized)) {
      return extractPdf(data);
    }
    if (isTextMimeType(normalized)) {
      return extractText(data);
    }
    throw new IllegalArgumentException("Unsupported document type: " + mimeType);
  }

  public boolean supports(String mimeType) {
    String normalized = normalizeMimeType(mimeType);
    return "application/pdf".equals(normalized) || isTextMimeType(normalized);
  }

  private List<PageText> extractPdf(byte[] data) {
    try {
      PdfDocumentReaderConfig config =
          PdfDocumentReaderConfig.builder()
              .withPagesPerDocument(1)
              .withPageTopMargin(0)
              .withPageBottomMargin(0)
              .withPageExtractedTextFormatter(
                  ExtractedTextFormatter.builder()
                      .withNumberOfTopTextLinesToDelete(0)
                      .build())
              .build();
      PagePdfDocumentReader reader =
          new PagePdfDocumentReader(new ByteArrayResource(data), config);
      return reader.read().stream()
          .map(
              document ->
                  new PageText(
                      pageMetadata(document, PagePdfDocumentReader.METADATA_START_PAGE_NUMBER),
                      normalizePdfText(document.getText())))
          .filter(page -> !page.text().isBlank())
          .toList();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("PDF could not be read", e);
    }
  }

  private static Integer pageMetadata(Document document, String key) {
    Object value = document.getMetadata().get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private List<PageText> extractText(byte[] data) {
    TextReader reader = new TextReader(new ByteArrayResource(data));
    reader.setCharset(StandardCharsets.UTF_8);
    return reader.get().stream()
        .map(Document::getText)
        .map(DocumentTextExtractor::normalizeText)
        .filter(text -> !text.isBlank())
        .map(text -> new PageText(null, text))
        .toList();
  }

  private static boolean isTextMimeType(String mimeType) {
    return mimeType.startsWith("text/")
        || "application/json".equals(mimeType)
        || "application/xml".equals(mimeType)
        || "application/x-ndjson".equals(mimeType)
        || "application/yaml".equals(mimeType);
  }

  private static String normalizeMimeType(String mimeType) {
    if (mimeType == null || mimeType.isBlank()) {
      return "application/octet-stream";
    }
    int semicolon = mimeType.indexOf(';');
    return (semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType).trim().toLowerCase();
  }

  private static String normalizeText(String text) {
    return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  private static String normalizePdfText(String text) {
    return normalizeText(text).replaceAll("[ \\t\\x0B\\f]{2,}", " ");
  }

  public record PageText(Integer page, String text) {}
}
