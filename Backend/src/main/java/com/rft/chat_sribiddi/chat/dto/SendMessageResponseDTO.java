package com.rft.chat_sribiddi.chat.dto;

public record SendMessageResponseDTO(
    String message, Long promptTokens, Long generationTokens, Long totalTokens, String chatTitle) {}
