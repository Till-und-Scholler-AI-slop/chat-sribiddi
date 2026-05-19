package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.billing.ModelPricingLookup;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
        return withOpenRouterFreeRouter(live);
      }
    }
    return List.copyOf(properties.getOpenrouter());
  }

  private List<ModelCatalogProperties.ModelOption> withOpenRouterFreeRouter(
      List<ModelCatalogProperties.ModelOption> models) {
    if (models.stream()
        .filter(Objects::nonNull)
        .anyMatch(model -> ModelPricingLookup.OPENROUTER_FREE_MODEL_ID.equals(model.getId()))) {
      return models;
    }
    ModelCatalogProperties.ModelOption freeRouter =
        properties.getOpenrouter().stream()
            .filter(Objects::nonNull)
            .filter(model -> ModelPricingLookup.OPENROUTER_FREE_MODEL_ID.equals(model.getId()))
            .findFirst()
            .orElseGet(
                () -> {
                  ModelCatalogProperties.ModelOption option =
                      new ModelCatalogProperties.ModelOption(
                          ModelPricingLookup.OPENROUTER_FREE_MODEL_ID, "OpenRouter Free");
                  option.setPromptPrice("0");
                  option.setCompletionPrice("0");
                  return option;
                });
    List<ModelCatalogProperties.ModelOption> withFreeRouter = new ArrayList<>();
    withFreeRouter.add(freeRouter);
    withFreeRouter.addAll(models);
    return List.copyOf(withFreeRouter);
  }

  public List<ModelCatalogProperties.ModelOption> compactModels(
      Provider provider, List<ModelCatalogProperties.ModelOption> models) {
    if (models == null || models.isEmpty()) {
      return models == null ? List.of() : models;
    }

    List<String> featuredIds = featuredIds(provider);
    LinkedHashSet<ModelCatalogProperties.ModelOption> selected = new LinkedHashSet<>();
    for (String id : featuredIds) {
      models.stream()
          .filter(Objects::nonNull)
          .filter(model -> id.equalsIgnoreCase(model.getId()))
          .findFirst()
          .ifPresent(selected::add);
    }

    if (selected.size() < 3) {
      models.stream()
          .filter(Objects::nonNull)
          .sorted(Comparator.comparing(this::sortLabel, String.CASE_INSENSITIVE_ORDER))
          .forEach(
              model -> {
                if (selected.size() < 3) {
                  selected.add(model);
                }
              });
    }

    return new ArrayList<>(selected);
  }

  private List<String> featuredIds(Provider provider) {
    ModelCatalogProperties.FeaturedModels featured = properties.getFeatured();
    if (featured == null) {
      return List.of();
    }
    return switch (provider) {
      case OPENAI -> safeList(featured.getOpenai());
      case GOOGLE -> safeList(featured.getGoogle());
      case CLAUDE -> safeList(featured.getClaude());
      case OPENROUTER -> safeList(featured.getOpenrouter());
      case BRAVE_SEARCH -> List.of();
    };
  }

  private List<String> safeList(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
  }

  private String sortLabel(ModelCatalogProperties.ModelOption model) {
    String label = model.getLabel();
    if (label != null && !label.isBlank()) {
      return label.toLowerCase(Locale.ROOT);
    }
    String id = model.getId();
    return id == null ? "" : id.toLowerCase(Locale.ROOT);
  }
}
