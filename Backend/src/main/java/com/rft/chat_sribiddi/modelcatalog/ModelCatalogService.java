package com.rft.chat_sribiddi.modelcatalog;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelCatalogService {

  private final ModelCatalogProperties properties;
  private final ModelFetchService modelFetchService;

  public List<ModelCatalogProperties.ModelOption> listOpenAiModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchOpenAiModels(apiKey);
      if (!live.isEmpty()) {
        return live;
      }
    }
    return List.copyOf(properties.getOpenai());
  }

  public List<ModelCatalogProperties.ModelOption> listGoogleModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchGoogleModels(apiKey);
      if (!live.isEmpty()) {
        return live;
      }
    }
    return List.copyOf(properties.getGoogle());
  }

  public List<ModelCatalogProperties.ModelOption> listClaudeModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchClaudeModels(apiKey);
      if (!live.isEmpty()) {
        return live;
      }
    }
    return List.copyOf(properties.getClaude());
  }

  public List<ModelCatalogProperties.ModelOption> listOpenRouterModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchOpenRouterModels(apiKey);
      if (!live.isEmpty()) {
        return live;
      }
    }
    return List.copyOf(properties.getOpenrouter());
  }
}
