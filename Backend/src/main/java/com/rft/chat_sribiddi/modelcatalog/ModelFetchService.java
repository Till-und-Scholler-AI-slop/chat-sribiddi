package com.rft.chat_sribiddi.modelcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ModelFetchService {

  private static final Logger log = LoggerFactory.getLogger(ModelFetchService.class);

  private final RestClient restClient;

  public ModelFetchService() {
    this.restClient = RestClient.builder().build();
  }

  public List<ModelOption> fetchOpenAiModels(String apiKey) {
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
      models.sort(Comparator.comparing(ModelOption::getId));
      return models;
    } catch (Exception e) {
      log.warn("Failed to fetch OpenAI models: {}", e.getMessage());
      return List.of();
    }
  }

  public List<ModelOption> fetchGoogleModels(String apiKey) {
    try {
      JsonNode response =
          restClient
              .get()
              .uri("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey)
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
      models.sort(Comparator.comparing(ModelOption::getId));
      return models;
    } catch (Exception e) {
      log.warn("Failed to fetch Google models: {}", e.getMessage());
      return List.of();
    }
  }

  public List<ModelOption> fetchClaudeModels(String apiKey) {
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
      models.sort(Comparator.comparing(ModelOption::getId));
      return models;
    } catch (Exception e) {
      log.warn("Failed to fetch Claude models: {}", e.getMessage());
      return List.of();
    }
  }

  private boolean isRelevantOpenAiModel(String id) {
    return id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4");
  }

  private boolean isRelevantGoogleModel(String id) {
    return id.startsWith("gemini-");
  }
}
