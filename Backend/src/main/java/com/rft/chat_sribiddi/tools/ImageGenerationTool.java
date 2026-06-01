package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialProperties;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialProvider;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialResolver;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ImageGenerationTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> ALLOWED_SIZES = List.of("1024x1024", "1024x1536", "1536x1024");

  private final String userId;
  private final ToolCredentialResolver credentialResolver;
  private final ToolCredentialProperties properties;

  public ImageGenerationTool(
      String userId, ToolCredentialResolver credentialResolver, ToolCredentialProperties properties) {
    this.userId = userId;
    this.credentialResolver = credentialResolver;
    this.properties = properties;
  }

  @Override
  public String getName() {
    return "image-generation";
  }

  @Override
  public String getDisplayName() {
    return "Image Generation";
  }

  @Override
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
  }

  @Override
  public boolean requiresUserConfig() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String prompt = stringValue(parameters, "prompt");
    String size = stringValue(parameters, "size");
    return ToolResult.success(generateImage(prompt, size));
  }

  @Tool(
      description =
          "Generate one image from a textual prompt. Returns structured JSON containing image URL or base64 data, model, and size.")
  public String generateImage(
      @ToolParam(description = "Detailed image prompt") String prompt,
      @ToolParam(description = "Image size, one of 1024x1024, 1024x1536, or 1536x1024")
          String size) {
    if (prompt == null || prompt.isBlank()) {
      return unavailable("MISSING_PROMPT", "Image generation needs a prompt.");
    }
    String apiKey =
        credentialResolver.resolve(userId, ToolCredentialProvider.OPENAI_IMAGE).orElse(null);
    if (apiKey == null || apiKey.isBlank()) {
      return unavailable(
          "MANAGED_PROVIDER_UNCONFIGURED",
          "Image generation is unavailable until an OpenAI image key is configured.");
    }

    String resolvedSize = ALLOWED_SIZES.contains(size) ? size : "1024x1024";
    try {
      OpenAiImageOptions options =
          OpenAiImageOptions.builder()
              .apiKey(apiKey)
              .model(properties.getOpenaiImageModel())
              .size(resolvedSize)
              .N(1)
              .build();
      ImageResponse response = new OpenAiImageModel(options).call(new ImagePrompt(prompt, options));
      ImageGeneration generation = response.getResult();
      Image image = generation == null ? null : generation.getOutput();
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", "image");
      result.put("model", properties.getOpenaiImageModel());
      result.put("size", resolvedSize);
      result.put("prompt", prompt);
      result.put("url", image == null ? null : image.getUrl());
      result.put("b64Json", image == null ? null : image.getB64Json());
      return MAPPER.writeValueAsString(result);
    } catch (RuntimeException | IOException e) {
      return unavailable("IMAGE_PROVIDER_ERROR", e.getMessage());
    }
  }

  private static String unavailable(String code, String message) {
    try {
      return MAPPER.writeValueAsString(
          Map.of("type", "image", "errorCode", code, "message", message == null ? "" : message));
    } catch (IOException _) {
      return message == null ? code : message;
    }
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }
}
