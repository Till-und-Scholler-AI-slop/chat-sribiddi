package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.chat.service.OpenRouterProviderProperties;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelFetchServiceTest {

  private ModelFetchService service;
  private Method isRelevantOpenAiModel;

  @BeforeEach
  void setUp() throws Exception {
    service = new ModelFetchService(new OpenRouterProviderProperties());
    isRelevantOpenAiModel =
        ModelFetchService.class.getDeclaredMethod("isRelevantOpenAiModel", String.class);
    isRelevantOpenAiModel.setAccessible(true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gpt-4.1-2025-04-14",
        "gpt-4.1-mini-2025-04-14",
        "gpt-5.2",
        "gpt-5.2-mini",
        "gpt-5.2-nano",
        "o1-2024-12-17",
        "o3-2025-04-16",
        "o4-mini-2025-04-16",
        "o5-preview"
      })
  void isRelevantOpenAiModel_acceptsExpectedModels(String modelId) throws Exception {
    assertThat((boolean) isRelevantOpenAiModel.invoke(service, modelId)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"dall-e-3", "tts-1", "whisper-1", "text-embedding-3-large", "babbage"})
  void isRelevantOpenAiModel_rejectsIrrelevantModels(String modelId) throws Exception {
    assertThat((boolean) isRelevantOpenAiModel.invoke(service, modelId)).isFalse();
  }

  @Test
  void isRelevantGoogleModel_acceptsGeminiPrefix() throws Exception {
    Method isRelevantGoogleModel =
        ModelFetchService.class.getDeclaredMethod("isRelevantGoogleModel", String.class);
    isRelevantGoogleModel.setAccessible(true);

    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemini-3.1-pro-preview")).isTrue();
    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemini-2.5-flash")).isTrue();
    assertThat((boolean) isRelevantGoogleModel.invoke(service, "text-bison")).isFalse();
  }

  @Test
  void toOpenRouterModelOption_mapsPricingAndContextFields() throws Exception {
    Method toOpenRouterModelOption =
        ModelFetchService.class.getDeclaredMethod(
            "toOpenRouterModelOption", com.fasterxml.jackson.databind.JsonNode.class);
    toOpenRouterModelOption.setAccessible(true);

    var payload =
        new ObjectMapper()
            .readTree(
                """
                {
                  "id": "openai/gpt-4o",
                  "canonical_slug": "openai/gpt-4o",
                  "name": "GPT-4o",
                  "created": 1730000000,
                  "description": "Fast flagship model",
                  "context_length": 128000,
                  "pricing": {
                    "prompt": "0.000005",
                    "completion": "0.000015",
                    "request": "0",
                    "image": "0"
                  },
                  "architecture": {
                    "modality": "text->text",
                    "input_modalities": ["text", "image"],
                    "output_modalities": ["text"]
                  },
                  "top_provider": {
                    "max_completion_tokens": 8192
                  },
                  "supported_parameters": ["temperature", "top_p"]
                }
                """);

    var option =
        (ModelCatalogProperties.ModelOption) toOpenRouterModelOption.invoke(service, payload);

    assertThat(option.getId()).isEqualTo("openai/gpt-4o");
    assertThat(option.getLabel()).isEqualTo("GPT-4o");
    assertThat(option.getContextLength()).isEqualTo(128000);
    assertThat(option.getPromptPrice()).isEqualTo("0.000005");
    assertThat(option.getCompletionPrice()).isEqualTo("0.000015");
    assertThat(option.getMaxCompletionTokens()).isEqualTo(8192);
    assertThat(option.getSupportedParameters()).containsExactly("temperature", "top_p");
    assertThat(option.getInputModalities()).containsExactly("text", "image");
  }
}
