package com.rft.chat_sribiddi.chat.dto;

/**
 * Server-Sent Event payload for streaming chat responses.
 *
 * @param type one of {@code text}, {@code reasoning}, {@code done}, {@code error}
 * @param content text chunk (for {@code text}/{@code reasoning}/{@code error})
 * @param promptTokens token count (only on {@code done})
 * @param generationTokens token count (only on {@code done})
 * @param totalTokens token count (only on {@code done})
 */
public record StreamEventDTO(
    String type,
    String content,
    Long promptTokens,
    Long generationTokens,
    Long totalTokens) {}
