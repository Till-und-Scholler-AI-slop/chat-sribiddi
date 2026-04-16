package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.chat.service.OpenRouterProviderProperties;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class ModelFetchServiceTest {

  private ModelFetchService service;
  private Method isRelevantOpenAiModel;
  private OpenRouterProviderProperties openRouterProperties;
  private ModelCatalogHttpProperties httpProperties;

  @BeforeEach
  void setUp() throws Exception {
    openRouterProperties = new OpenRouterProviderProperties();
    httpProperties = new ModelCatalogHttpProperties();
    service = new ModelFetchService(openRouterProperties, httpProperties);
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
  void isRelevantGoogleModel_acceptsGeminiAndGemmaPrefix() throws Exception {
    Method isRelevantGoogleModel =
        ModelFetchService.class.getDeclaredMethod("isRelevantGoogleModel", String.class);
    isRelevantGoogleModel.setAccessible(true);

    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemini-3.1-pro-preview")).isTrue();
    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemini-2.5-flash")).isTrue();
    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemma-3-27b-it")).isTrue();
    assertThat((boolean) isRelevantGoogleModel.invoke(service, "gemma-3-4b-it")).isTrue();
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

  @Test
  void fetchGoogleModels_parsesJsonResponseFromStringBody() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    service = new ModelFetchService(builder.build(), new ObjectMapper(), openRouterProperties);

    server
        .expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models"))
        .andExpect(method(GET))
        .andExpect(header("x-goog-api-key", "goog-key"))
        .andRespond(
            withSuccess(
                """
                {
                  "models": [
                    { "name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash" },
                    { "name": "models/gemma-3-27b-it", "displayName": "Gemma 3 27B" },
                    { "name": "models/text-bison", "displayName": "Text Bison" }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    var models = service.fetchGoogleModels("goog-key");

    assertThat(models).extracting(ModelCatalogProperties.ModelOption::getId)
        .containsExactly("gemini-2.5-flash", "gemma-3-27b-it");
    assertThat(models).extracting(ModelCatalogProperties.ModelOption::getLabel)
        .containsExactly("Gemini 2.5 Flash", "Gemma 3 27B");
  }

  @Test
  void fetchOpenRouterModels_parsesCatalogResponseFromStringBody() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    service = new ModelFetchService(builder.build(), new ObjectMapper(), openRouterProperties);

    server
        .expect(requestTo(openRouterProperties.getModelCatalogUrl()))
        .andExpect(method(GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer or-key"))
        .andRespond(
            withSuccess(
                """
                {
                  "data": [
                    {
                      "id": "openrouter/auto",
                      "name": "OpenRouter Auto"
                    },
                    {
                      "id": "openai/gpt-4o",
                      "canonical_slug": "openai/gpt-4o",
                      "name": "GPT-4o",
                      "context_length": 128000,
                      "pricing": {
                        "prompt": "0.000005",
                        "completion": "0.000015"
                      }
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    var models = service.fetchOpenRouterModels("or-key");

    assertThat(models).extracting(ModelCatalogProperties.ModelOption::getId)
        .containsExactly("openai/gpt-4o", "openrouter/auto");
    assertThat(models).extracting(ModelCatalogProperties.ModelOption::getLabel)
        .containsExactly("GPT-4o", "OpenRouter Auto");
    assertThat(models.getFirst().getPromptPrice()).isEqualTo("0.000005");
    assertThat(models.getFirst().getCompletionPrice()).isEqualTo("0.000015");
  }

  @Test
  void springContextInstantiatesServiceUsingInjectedPropertiesConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(OpenRouterProviderProperties.class, ModelCatalogHttpProperties.class, ModelFetchService.class);

      context.refresh();

      ModelFetchService modelFetchService = context.getBean(ModelFetchService.class);
      OpenRouterProviderProperties injectedProperties =
          context.getBean(OpenRouterProviderProperties.class);

      assertThat(modelFetchService).isNotNull();
      assertThat(ReflectionTestUtils.getField(modelFetchService, "openRouterProperties"))
          .isSameAs(injectedProperties);
    }
  }
}
