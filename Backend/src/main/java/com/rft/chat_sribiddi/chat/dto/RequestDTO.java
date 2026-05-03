package com.rft.chat_sribiddi.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Payload for sending a chat message.
 *
 * @param content user message
 * @param provider target model provider
 * @param modelId model identifier for the provider
 * @param apiKey deprecated compatibility field; request-supplied API keys are ignored
 */
public record RequestDTO(
    @NotBlank(message = "content must not be blank") String content,
    @NotNull(message = "provider is required") Provider provider,
    @NotBlank(message = "modelId must not be blank") String modelId,
    @Deprecated @JsonIgnore String apiKey,
    @Size(max = 5, message = "media supports up to 5 attachments")
        List<@NotNull(message = "media items must not be null") @Valid MediaAttachment> media,
    Boolean webSearchEnabled,
    List<@Valid ToolConsent> toolConsents) {

  public RequestDTO(
      String content,
      Provider provider,
      String modelId,
      String apiKey,
      List<MediaAttachment> media,
      Boolean webSearchEnabled) {
    this(content, provider, modelId, apiKey, media, webSearchEnabled, null);
  }

  public record MediaAttachment(
      @NotBlank(message = "mimeType must not be blank") String mimeType,
      @NotBlank(message = "data must not be blank") String data) {}

  public record ToolConsent(
      @NotBlank(message = "toolName must not be blank") String toolName,
      @NotBlank(message = "riskLevel must not be blank") String riskLevel,
      Map<String, Object> args) {}

  public enum Provider {
    OPENAI,
    GOOGLE,
    CLAUDE,
    OPENROUTER,
    BRAVE_SEARCH
  }
}
