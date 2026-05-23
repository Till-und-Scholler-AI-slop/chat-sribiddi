package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.service.DocumentTextExtractor.PageText;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {

  private static final int TARGET_TOKENS = 1_200;
  private static final int OVERLAP_TOKENS = 120;
  private static final int CHARS_PER_TOKEN = 4;
  private static final int TARGET_CHARS = TARGET_TOKENS * CHARS_PER_TOKEN;
  private static final int OVERLAP_CHARS = OVERLAP_TOKENS * CHARS_PER_TOKEN;

  public List<DocumentChunk> chunk(List<PageText> pages) {
    List<DocumentChunk> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Integer pageStart = null;
    Integer pageEnd = null;

    for (PageText page : pages) {
      if (page.text() == null || page.text().isBlank()) {
        continue;
      }
      if (pageStart == null) {
        pageStart = page.page();
      }
      pageEnd = page.page();
      if (!current.isEmpty()) {
        current.append("\n\n");
      }
      current.append(page.text());

      while (current.length() >= TARGET_CHARS) {
        String text = current.substring(0, TARGET_CHARS).trim();
        if (!text.isBlank()) {
          chunks.add(new DocumentChunk(chunks.size(), pageStart, pageEnd, text, estimateTokens(text)));
        }
        int keepFrom = Math.max(0, TARGET_CHARS - OVERLAP_CHARS);
        current.delete(0, keepFrom);
        pageStart = pageEnd;
      }
    }

    String trailing = current.toString().trim();
    if (!trailing.isBlank()) {
      chunks.add(new DocumentChunk(chunks.size(), pageStart, pageEnd, trailing, estimateTokens(trailing)));
    }
    return chunks;
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
