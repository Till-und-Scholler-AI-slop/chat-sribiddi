package com.rft.chat_sribiddi.chat.dto;

/**
 * Server-Sent Event payload for streaming chat responses.
 *
 * @param type one of {@code text}, {@code reasoning}, {@code done}, {@code error}
 * @param content text chunk (for {@code text}/{@code reasoning}/{@code error})
 * @param promptTokens token count (only on {@code done})
 * @param generationTokens token count (only on {@code done})
 * @param totalTokens token count (only on {@code done})
 * @param chatTitle updated chat title when auto-set from the first message (only on {@code done})
 * @param messageId persisted assistant message id (only on {@code done})
 * @param hasToolCalls whether the persisted assistant message has stored tool call logs
 */
public record StreamEventDTO(
    String type,
    String content,
    Long promptTokens,
    Long generationTokens,
    Long totalTokens,
    String chatTitle,
    Long messageId,
    Boolean hasToolCalls) {}
