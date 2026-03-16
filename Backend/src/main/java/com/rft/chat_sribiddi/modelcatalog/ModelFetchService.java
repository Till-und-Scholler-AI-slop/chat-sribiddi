package com.rft.chat_sribiddi.modelcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.Pricing;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class ModelFetchService {

  private final RestClient restClient;

  /** Cache keyed by "{provider}:{sha256(apiKey)}" with a 5-minute TTL to avoid hammering provider APIs. */
  private final Cache<String, List<ModelOption>> modelCache;

  public ModelFetchService() {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    this.modelCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(500).build();
  }

  public List<ModelOption> fetchOpenAiModels(String apiKey) {
    return modelCache.get(
        cacheKey("openai", apiKey),
        k -> {
          try {
            JsonNode response =
                restClient
                    .get()
                    .uri("https://api.openai.com/v1/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);

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
            models.sort(Comparator.comparing(ModelOption::getLabel).thenComparing(ModelOption::getId));
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
                restClient
                    .get()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models")
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(JsonNode.class);

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
            models.sort(Comparator.comparing(ModelOption::getLabel).thenComparing(ModelOption::getId));
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
                restClient
                    .get()
                    .uri("https://api.anthropic.com/v1/models")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .retrieve()
                    .body(JsonNode.class);

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
            models.sort(Comparator.comparing(ModelOption::getLabel).thenComparing(ModelOption::getId));
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
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri("https://openrouter.ai/api/v1/models");
            if (apiKey != null && !apiKey.isBlank()) {
              request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            JsonNode response = request.retrieve().body(JsonNode.class);
            if (response == null || !response.has("data")) {
              return List.of();
            }

            List<ModelOption> models = new ArrayList<>();
            for (JsonNode model : response.get("data")) {
              String id = firstTextValue(model, "id", "canonical_slug");
              if (id == null || id.isBlank()) {
                continue;
              }
              String label = textValue(model, "name");
              models.add(
                  new ModelOption(
                      id,
                      (label == null || label.isBlank()) ? id : label,
                      textValue(model, "description"),
                      firstLongValue(model, "context_length", "top_provider.context_length"),
                      pricing(model.get("pricing"))));
            }
            models.sort(Comparator.comparing(ModelOption::getLabel).thenComparing(ModelOption::getId));
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

  /** Returns a cache key using a SHA-256 hash of the API key to avoid storing raw credentials. */
  private String cacheKey(String provider, String apiKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((apiKey == null ? "<public>" : apiKey).getBytes(StandardCharsets.UTF_8));
      return provider + ":" + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be available in all JVM implementations
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private String textValue(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    String value = node.get(field).asText();
    return value == null || value.isBlank() ? null : value;
  }

  private Long longValue(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    return node.get(field).asLong();
  }

  private String firstTextValue(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = pathTextValue(node, field);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private Long firstLongValue(JsonNode node, String... fields) {
    for (String field : fields) {
      Long value = pathLongValue(node, field);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String pathTextValue(JsonNode node, String path) {
    JsonNode resolved = pathValue(node, path);
    if (resolved == null || resolved.isNull()) {
      return null;
    }
    String value = resolved.asText();
    return value == null || value.isBlank() ? null : value;
  }

  private Long pathLongValue(JsonNode node, String path) {
    JsonNode resolved = pathValue(node, path);
    if (resolved == null || resolved.isNull()) {
      return null;
    }
    return resolved.asLong();
  }

  private JsonNode pathValue(JsonNode node, String path) {
    if (node == null || path == null || path.isBlank()) {
      return null;
    }

    JsonNode current = node;
    for (String part : path.split("\\.")) {
      if (current == null || !current.hasNonNull(part)) {
        return null;
      }
      current = current.get(part);
    }
    return current;
  }

  private Pricing pricing(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }

    Pricing pricing =
        new Pricing(
            textValue(node, "prompt"),
            textValue(node, "completion"),
            textValue(node, "image"),
            textValue(node, "request"));

    return pricing.getPrompt() == null
            && pricing.getCompletion() == null
            && pricing.getImage() == null
            && pricing.getRequest() == null
        ? null
        : pricing;
  }
}
