package com.rft.chat_sribiddi.modelcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.chat.service.OpenRouterProviderProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ModelFetchService {

  private static final Logger log = LoggerFactory.getLogger(ModelFetchService.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final OpenRouterProviderProperties openRouterProperties;

  /** Cache keyed by "{provider}:{sha256(apiKey)}" with a 5-minute TTL to avoid hammering provider APIs. */
  private final Cache<String, List<ModelOption>> modelCache;

  @Autowired
  public ModelFetchService(OpenRouterProviderProperties openRouterProperties) {
    this(createRestClient(), new ObjectMapper(), openRouterProperties);
  }

  /** Package-private for focused tests that need to inject a mocked RestClient. */
  ModelFetchService(
      RestClient restClient,
      ObjectMapper objectMapper,
      OpenRouterProviderProperties openRouterProperties) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.openRouterProperties = openRouterProperties;
    this.modelCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(500).build();
  }

  public List<ModelOption> fetchOpenAiModels(String apiKey) {
    return modelCache.get(
        cacheKey("openai", apiKey),
        k -> {
          try {
            JsonNode response =
                getJson(
                    restClient
                        .get()
                        .uri("https://api.openai.com/v1/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey));

            if (response == null || !response.has("data")) {
              return List.of();
            }

            List<ModelOption> models = new ArrayList<>();
            for (JsonNode model : response.get("data")) {
              String id = model.get("id").asText();
              if (isRelevantOpenAiModel(id)) {
                models.add(new ModelOption(id, id));
              }
            }
            models.sort(Comparator.comparing(ModelOption::getId));
            return models;
          } catch (Exception e) {
            log.warn("Failed to fetch OpenAI models: {}", e.getMessage());
            return List.of();
          }
        });
  }

  public List<ModelOption> fetchGoogleModels(String apiKey) {
    return modelCache.get(
        cacheKey("google", apiKey),
        k -> {
          try {
            JsonNode response =
                getJson(
                    restClient
                        .get()
                        .uri("https://generativelanguage.googleapis.com/v1beta/models")
                        .header("x-goog-api-key", apiKey));

            if (response == null || !response.has("models")) {
              return List.of();
            }

            List<ModelOption> models = new ArrayList<>();
            for (JsonNode model : response.get("models")) {
              String fullName = model.get("name").asText();
              String id = fullName.startsWith("models/") ? fullName.substring(7) : fullName;
              String displayName =
                  model.has("displayName") ? model.get("displayName").asText() : id;

              if (isRelevantGoogleModel(id)) {
                models.add(new ModelOption(id, displayName));
              }
            }
            models.sort(Comparator.comparing(ModelOption::getId));
            return models;
          } catch (Exception e) {
            log.warn("Failed to fetch Google models: {}", e.getMessage());
            return List.of();
          }
        });
  }

  public List<ModelOption> fetchClaudeModels(String apiKey) {
    return modelCache.get(
        cacheKey("claude", apiKey),
        k -> {
          try {
            JsonNode response =
                getJson(
                    restClient
                        .get()
                        .uri("https://api.anthropic.com/v1/models")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01"));

            if (response == null || !response.has("data")) {
              return List.of();
            }

            List<ModelOption> models = new ArrayList<>();
            for (JsonNode model : response.get("data")) {
              String id = model.get("id").asText();
              String displayName =
                  model.has("display_name") ? model.get("display_name").asText() : id;
              models.add(new ModelOption(id, displayName));
            }
            models.sort(Comparator.comparing(ModelOption::getId));
            return models;
          } catch (Exception e) {
            log.warn("Failed to fetch Claude models: {}", e.getMessage());
            return List.of();
          }
        });
  }

  public List<ModelOption> fetchOpenRouterModels(String apiKey) {
    return modelCache.get(
        cacheKey("openrouter", apiKey),
        k -> {
          try {
            JsonNode response =
                getJson(
                    restClient
                        .get()
                        .uri(openRouterProperties.getModelCatalogUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey));

            if (response == null || !response.has("data")) {
              return List.of();
            }

            List<ModelOption> models = new ArrayList<>();
            for (JsonNode model : response.get("data")) {
              ModelOption option = toOpenRouterModelOption(model);
              if (option != null) {
                models.add(option);
              }
            }
            models.sort(
                Comparator.comparing(ModelOption::getLabel, Comparator.nullsLast(String::compareTo))
                    .thenComparing(ModelOption::getId));
            return models;
          } catch (Exception e) {
            log.warn("Failed to fetch OpenRouter models: {}", e.getMessage());
            return List.of();
          }
        });
  }

  private boolean isRelevantOpenAiModel(String id) {
    return id.startsWith("gpt-") || id.matches("^o\\d.*");
  }

  private boolean isRelevantGoogleModel(String id) {
    return id.startsWith("gemini-");
  }

  /**
   * Parses provider responses from a raw JSON string because these endpoints can fail when
   * {@link RestClient} is asked to deserialize directly to {@link JsonNode}.
   */
  private JsonNode getJson(RestClient.RequestHeadersSpec<?> request) throws IOException {
    String responseBody = request.retrieve().body(String.class);
    if (responseBody == null || responseBody.isBlank()) {
      return null;
    }
    return objectMapper.readTree(responseBody);
  }

  private ModelOption toOpenRouterModelOption(JsonNode model) {
    String id = textOrNull(model, "id");
    if (id == null || id.isBlank()) {
      return null;
    }

    String label = firstNonBlank(textOrNull(model, "name"), textOrNull(model, "canonical_slug"), id);

    ModelOption option = new ModelOption(id, label);
    option.setCanonicalSlug(textOrNull(model, "canonical_slug"));
    option.setCreated(longOrNull(model, "created"));
    option.setDescription(textOrNull(model, "description"));
    Integer topProviderContextLength = integerOrNull(model.path("top_provider"), "context_length", null);
    option.setContextLength(integerOrNull(model, "context_length", topProviderContextLength));
    option.setMaxCompletionTokens(integerOrNull(model.path("top_provider"), "max_completion_tokens", null));

    JsonNode pricing = model.path("pricing");
    option.setPromptPrice(textOrNull(pricing, "prompt"));
    option.setCompletionPrice(textOrNull(pricing, "completion"));
    option.setRequestPrice(textOrNull(pricing, "request"));
    option.setImagePrice(textOrNull(pricing, "image"));

    JsonNode architecture = model.path("architecture");
    option.setModality(textOrNull(architecture, "modality"));
    option.setInputModalities(stringList(architecture.path("input_modalities")));
    option.setOutputModalities(stringList(architecture.path("output_modalities")));
    option.setSupportedParameters(stringList(model.path("supported_parameters")));
    return option;
  }

  private String textOrNull(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    JsonNode value = node.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private Long longOrNull(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    JsonNode value = node.path(fieldName);
    if (!value.canConvertToLong()) {
      return null;
    }
    return value.longValue();
  }

  private Integer integerOrNull(JsonNode node, String fieldName, Integer fallback) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return fallback;
    }
    JsonNode value = node.path(fieldName);
    if (!value.canConvertToInt()) {
      return fallback;
    }
    return value.intValue();
  }

  private List<String> stringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      String value = item.asText();
      if (value != null && !value.isBlank()) {
        values.add(value);
      }
    }
    return values;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** Returns a cache key using a SHA-256 hash of the API key to avoid storing raw credentials. */
  private String cacheKey(String provider, String apiKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
      return provider + ":" + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be available in all JVM implementations
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static RestClient createRestClient() {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
