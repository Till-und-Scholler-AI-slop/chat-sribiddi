package com.rft.chat_sribiddi.chat.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

  public List<PageText> extract(String mimeType, byte[] data) {
    String normalized = normalizeMimeType(mimeType);
    if ("application/pdf".equals(normalized)) {
      return extractPdf(data);
    }
    if (isTextMimeType(normalized)) {
      return List.of(new PageText(null, normalizeText(new String(data, java.nio.charset.StandardCharsets.UTF_8))));
    }
    throw new IllegalArgumentException("Unsupported document type: " + mimeType);
  }

  public boolean supports(String mimeType) {
    String normalized = normalizeMimeType(mimeType);
    return "application/pdf".equals(normalized) || isTextMimeType(normalized);
  }

  private List<PageText> extractPdf(byte[] data) {
    try (PDDocument document = Loader.loadPDF(data)) {
      if (document.isEncrypted()) {
        throw new IllegalArgumentException("Encrypted PDFs are not supported");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      List<PageText> pages = new ArrayList<>();
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = normalizeText(stripper.getText(document));
        if (!text.isBlank()) {
          pages.add(new PageText(page, text));
        }
      }
      return pages;
    } catch (IOException e) {
      throw new IllegalArgumentException("PDF could not be read", e);
    }
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

  public record PageText(Integer page, String text) {}
}
