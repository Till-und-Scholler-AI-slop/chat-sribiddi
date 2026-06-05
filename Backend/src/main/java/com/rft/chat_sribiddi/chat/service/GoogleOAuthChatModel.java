package com.rft.chat_sribiddi.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

final class GoogleOAuthChatModel implements ChatModel {

  private final ResolvedCredential credential;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  GoogleOAuthChatModel(
      ResolvedCredential credential, RestClient restClient, ObjectMapper objectMapper) {
    this.credential = credential;
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String modelId = options == null ? null : options.getModel();
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException("Google model id is required");
    }
    if (credential.accessToken() == null || credential.accessToken().isBlank()) {
      throw new IllegalArgumentException("Google OAuth access token is required");
    }
    JsonNode response = postGenerateContent(modelId, prompt);
    return new ChatResponse(List.of(new Generation(new AssistantMessage(extractText(response)))));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> Flux.just(call(prompt)));
  }

  private JsonNode postGenerateContent(String modelId, Prompt prompt) {
    try {
      String body =
          restClient
              .post()
              .uri(generateContentUri(modelId))
              .contentType(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential.accessToken())
              .headers(
                  headers -> {
                    if (credential.quotaProjectId() != null
                        && !credential.quotaProjectId().isBlank()) {
                      headers.set("x-goog-user-project", credential.quotaProjectId());
                    }
                  })
              .body(requestBody(prompt))
              .retrieve()
              .body(String.class);
      return objectMapper.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException("Google OAuth request failed", e);
    }
  }

  private String generateContentUri(String modelId) {
    return UriComponentsBuilder.fromUriString("https://generativelanguage.googleapis.com")
        .path("/v1beta/models/{model}:generateContent")
        .buildAndExpand(modelId)
        .toUriString();
  }

  private Map<String, Object> requestBody(Prompt prompt) {
    Map<String, Object> body = new LinkedHashMap<>();
    List<Map<String, Object>> contents = new ArrayList<>();
    List<String> systemTexts = new ArrayList<>();
    for (Message message : prompt.getInstructions()) {
      if (message.getMessageType() == MessageType.SYSTEM) {
        if (hasText(message.getText())) {
          systemTexts.add(message.getText());
        }
        continue;
      }
      Map<String, Object> content = toContent(message);
      if (!content.isEmpty()) {
        contents.add(content);
      }
    }
    if (!systemTexts.isEmpty()) {
      body.put(
          "systemInstruction",
          Map.of("parts", List.of(Map.of("text", String.join("\n\n", systemTexts)))));
    }
    body.put("contents", contents);
    Map<String, Object> generationConfig = generationConfig(prompt.getOptions());
    if (!generationConfig.isEmpty()) {
      body.put("generationConfig", generationConfig);
    }
    return body;
  }

  private Map<String, Object> toContent(Message message) {
    List<Map<String, Object>> parts = new ArrayList<>();
    if (hasText(message.getText())) {
      parts.add(Map.of("text", message.getText()));
    }
    if (message instanceof UserMessage userMessage) {
      userMessage.getMedia().stream()
          .filter(media -> media.getMimeType() != null && media.getDataAsByteArray() != null)
          .forEach(
              media -> {
                Map<String, Object> inlineData = new LinkedHashMap<>();
                inlineData.put("mimeType", media.getMimeType().toString());
                inlineData.put(
                    "data", Base64.getEncoder().encodeToString(media.getDataAsByteArray()));
                parts.add(Map.of("inlineData", inlineData));
              });
    }
    if (parts.isEmpty()) {
      return Map.of();
    }
    return Map.of("role", role(message.getMessageType()), "parts", parts);
  }

  private String role(MessageType messageType) {
    return messageType == MessageType.ASSISTANT ? "model" : "user";
  }

  private Map<String, Object> generationConfig(ChatOptions options) {
    if (options == null) {
      return Map.of();
    }
    Map<String, Object> config = new LinkedHashMap<>();
    putIfPresent(config, "temperature", options.getTemperature());
    putIfPresent(config, "topP", options.getTopP());
    putIfPresent(config, "topK", options.getTopK());
    putIfPresent(config, "maxOutputTokens", options.getMaxTokens());
    if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
      config.put("stopSequences", options.getStopSequences());
    }
    return config;
  }

  private void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private String extractText(JsonNode response) {
    if (response == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    JsonNode candidates = response.path("candidates");
    if (candidates.isArray()) {
      for (JsonNode candidate : candidates) {
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
          for (JsonNode part : parts) {
            JsonNode textNode = part.path("text");
            if (textNode.isTextual()) {
              text.append(textNode.asText());
            }
          }
        }
      }
    }
    return text.toString();
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
