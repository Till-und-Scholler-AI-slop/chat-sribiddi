package com.rft.chat_sribiddi.chat.service;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

  private int contextMessageLimit = 20;

  /** Maximum number of messages returned when loading a chat conversation (default: 50). */
  private int messagePageSize = 50;

  /** Maximum number of chats returned per page (default: 50). */
  private int chatPageSize = 50;

  private int maxMediaAttachments = 5;

  private long maxMediaAttachmentBytes = 5L * 1024 * 1024;

  private long maxMediaTotalBytes = 20L * 1024 * 1024;

  /** SSE stream timeout in milliseconds (default: 30 minutes). */
  private long streamTimeoutMs = 1_800_000L;

  /** Maximum character length for auto-generated chat titles (default: 100). */
  private int maxChatTitleLength = 100;

  /**
   * Provider/model wildcard patterns that must not receive Spring AI tool definitions.
   *
   * <p>Format: {@code provider:model-pattern}, e.g. {@code google:gemma-*}. Matching is
   * case-insensitive and supports {@code *}/{@code ?} wildcards.
   */
  private List<String> toolDisabledModelPatterns = new ArrayList<>();

  private String systemPrompt =
      """
      You are a helpful assistant. Format every answer as clean, renderer-friendly Markdown.
      Use paragraphs, lists, tables, blockquotes, and fenced code blocks with language identifiers when helpful.
      Do not wrap the whole answer in a single code block or use raw HTML unless the user explicitly asks for it.
      """;

  private int minContextExcerptCandidateLength = 8;
  private int maxContextExcerptMatches = 5;
  private int maxContextExcerptLength = 2_000;
}
