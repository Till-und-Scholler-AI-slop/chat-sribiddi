package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.billing.ModelPricingLookup;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelCatalogService {

  private final ModelCatalogProperties properties;
  private final ModelFetchService modelFetchService;
  private final ModelPricingLookup modelPricingLookup;

  public List<ModelCatalogProperties.ModelOption> listOpenAiModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchOpenAiModels(apiKey);
      if (!live.isEmpty()) {
        modelPricingLookup.remember(Provider.OPENAI, live);
        return live;
      }
    }
    return List.copyOf(properties.getOpenai());
  }

  public List<ModelCatalogProperties.ModelOption> listGoogleModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchGoogleModels(apiKey);
      if (!live.isEmpty()) {
        modelPricingLookup.remember(Provider.GOOGLE, live);
        return live;
      }
    }
    return List.copyOf(properties.getGoogle());
  }

  public List<ModelCatalogProperties.ModelOption> listClaudeModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchClaudeModels(apiKey);
      if (!live.isEmpty()) {
        modelPricingLookup.remember(Provider.CLAUDE, live);
        return live;
      }
    }
    return List.copyOf(properties.getClaude());
  }

  public List<ModelCatalogProperties.ModelOption> listOpenRouterModels(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      List<ModelCatalogProperties.ModelOption> live = modelFetchService.fetchOpenRouterModels(apiKey);
      if (!live.isEmpty()) {
        modelPricingLookup.remember(Provider.OPENROUTER, live);
        return live;
      }
    }
    return List.copyOf(properties.getOpenrouter());
  }
}
