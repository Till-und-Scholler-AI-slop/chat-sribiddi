package com.rft.chat_sribiddi.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

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
    @Valid ChatRuntimeOptions options,
    List<@Valid ToolConsent> toolConsents) {

  public RequestDTO(
      String content,
      Provider provider,
      String modelId,
      String apiKey,
      List<MediaAttachment> media,
      Boolean webSearchEnabled) {
    this(content, provider, modelId, apiKey, media, webSearchEnabled, null, null);
  }

  public RequestDTO(
      String content,
      Provider provider,
      String modelId,
      String apiKey,
      List<MediaAttachment> media,
      Boolean webSearchEnabled,
      List<ToolConsent> toolConsents) {
    this(content, provider, modelId, apiKey, media, webSearchEnabled, null, toolConsents);
  }

  public record MediaAttachment(
      @NotBlank(message = "mimeType must not be blank") String mimeType,
      @NotBlank(message = "data must not be blank") String data) {}

  public record ToolConsent(
      @NotBlank(message = "toolName must not be blank") String toolName,
      @NotBlank(message = "riskLevel must not be blank") String riskLevel,
      Map<String, Object> args) {}

  public record ChatRuntimeOptions(
      @DecimalMin(value = "0.0", message = "temperature must be at least 0")
          @DecimalMax(value = "2.0", message = "temperature must be at most 2")
          Double temperature,
      @DecimalMin(value = "0.0", message = "topP must be at least 0")
          @DecimalMax(value = "1.0", message = "topP must be at most 1")
          Double topP,
      @Positive(message = "topK must be positive") @Max(value = 1000, message = "topK is too large")
          Integer topK,
      @Positive(message = "maxTokens must be positive")
          @Max(value = 1_000_000, message = "maxTokens is too large")
          Integer maxTokens,
      @DecimalMin(value = "-2.0", message = "frequencyPenalty must be at least -2")
          @DecimalMax(value = "2.0", message = "frequencyPenalty must be at most 2")
          Double frequencyPenalty,
      @DecimalMin(value = "-2.0", message = "presencePenalty must be at least -2")
          @DecimalMax(value = "2.0", message = "presencePenalty must be at most 2")
          Double presencePenalty,
      @Size(max = 8, message = "stopSequences supports up to 8 items")
          List<@NotBlank(message = "stop sequence must not be blank")
                  @Size(max = 200, message = "stop sequence is too long")
                  String>
              stopSequences,
      @Min(value = -1, message = "thinkingBudget must be -1 or greater") Integer thinkingBudget,
      GoogleGenAiThinkingLevel thinkingLevel,
      Boolean includeThoughts,
      Boolean googleSearchRetrieval,
      Boolean includeServerSideToolInvocations) {

    public ChatRuntimeOptions(
        Double temperature,
        Double topP,
        Integer topK,
        Integer maxTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        List<String> stopSequences) {
      this(
          temperature,
          topP,
          topK,
          maxTokens,
          frequencyPenalty,
          presencePenalty,
          stopSequences,
          null,
          null,
          null,
          null,
          null);
    }
  }

  public enum Provider {
    OPENAI,
    GOOGLE,
    CLAUDE,
    OPENROUTER,
    BRAVE_SEARCH
  }
}
