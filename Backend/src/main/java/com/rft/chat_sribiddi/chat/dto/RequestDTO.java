package com.rft.chat_sribiddi.chat.dto;

public record RequestDTO(String content, Provider provider, String modelId) {

    public enum Provider {
        OPENAI,
        GOOGLE
    }
}
