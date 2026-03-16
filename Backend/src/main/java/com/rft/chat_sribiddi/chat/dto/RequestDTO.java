package com.rft.chat_sribiddi.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Payload for sending a chat message.
 *
 * @param content user message
 * @param provider target model provider
 * @param modelId model identifier for the provider
 * @param apiKey user-supplied API key for the chosen provider (optional if stored)
 */
public record RequestDTO(
    @NotBlank(message = "content must not be blank") String content,
    @NotNull(message = "provider is required") Provider provider,
    @NotBlank(message = "modelId must not be blank") String modelId,
    String apiKey,
    @Size(max = 5, message = "media supports up to 5 attachments")
        List<@NotNull(message = "media items must not be null") @Valid MediaAttachment> media) {

  public record MediaAttachment(
      @NotBlank(message = "mimeType must not be blank") String mimeType,
      @NotBlank(message = "data must not be blank") String data) {}

  public enum Provider {
    OPENAI,
    GOOGLE,
    CLAUDE,
    OPENROUTER
  }
}
