package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves model pricing (USD per 1,000,000 tokens) for a given {@code (provider, modelId)} pair
 * so that {@link EntitlementService} can enforce price-based plan-gating without hardcoded model
 * ID whitelists.
 *
 * <p>Pricing is read from the fallback catalog configured in {@code ai.models.*}
 * ({@link ModelCatalogProperties}), which stores OpenRouter-style USD-per-token strings on
 * {@link ModelOption#getPromptPrice()} / {@link ModelOption#getCompletionPrice()}. If a model is
 * absent from the catalog but its id follows OpenRouter's {@code :free} suffix convention, it is
 * reported as zero-cost so that truly-free OpenRouter models stay available on the Free tier
 * without requiring a manual pricing override.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPricingLookup {

  private static final double TOKENS_PER_MILLION = 1_000_000d;

  private final ModelCatalogProperties catalog;
  private final Map<String, Pricing> livePricingByProviderAndModel = new ConcurrentHashMap<>();
  private final Map<String, Optional<Pricing>> configuredPricingByProviderAndModel =
      new ConcurrentHashMap<>();

  /** Model pricing in USD per 1,000,000 tokens. */
  public record Pricing(double inputUsdPerMillion, double outputUsdPerMillion) {}

  /**
   * Returns the pricing for the given {@code (provider, modelId)} if it can be determined.
   * Returns {@link Optional#empty()} when the model is unknown and no free-convention match can
   * be inferred.
   */
  public Optional<Pricing> find(Provider provider, String modelId) {
    if (provider == null || modelId == null || modelId.isBlank()) {
      return Optional.empty();
    }
    Pricing live = livePricingByProviderAndModel.get(key(provider, modelId));
    if (live != null) {
      return Optional.of(live);
    }

    Optional<Pricing> configured =
        configuredPricingByProviderAndModel.computeIfAbsent(
            key(provider, modelId), ignored -> findConfiguredPricing(provider, modelId));
    if (configured.isPresent()) {
      return configured;
    }

    // OpenRouter free-model convention: ids ending with ':free' are zero-cost.
    if (provider == Provider.OPENROUTER && modelId.endsWith(":free")) {
      return Optional.of(new Pricing(0.0, 0.0));
    }
    return Optional.empty();
  }

  /**
   * Stores pricing discovered from live provider catalogs so later plan checks for those same model
   * ids can use the provider-reported prices instead of treating them as unknown.
   */
  public void remember(Provider provider, List<ModelOption> models) {
    if (provider == null || models == null || models.isEmpty()) {
      return;
    }
    for (ModelOption model : models) {
      if (model == null || model.getId() == null || model.getId().isBlank()) {
        continue;
      }
      Double prompt = parseUsdPerMillion(model.getPromptPrice());
      Double completion = parseUsdPerMillion(model.getCompletionPrice());
      if (prompt != null && completion != null) {
        livePricingByProviderAndModel.put(key(provider, model.getId()), new Pricing(prompt, completion));
      }
    }
  }

  private Optional<Pricing> findConfiguredPricing(Provider provider, String modelId) {
    Optional<ModelOption> match =
        listFor(provider).stream().filter(opt -> modelId.equals(opt.getId())).findFirst();
    if (match.isEmpty()) {
      return Optional.empty();
    }

    Double prompt = parseUsdPerMillion(match.get().getPromptPrice());
    Double completion = parseUsdPerMillion(match.get().getCompletionPrice());
    if (prompt == null || completion == null) {
      return Optional.empty();
    }
    return Optional.of(new Pricing(prompt, completion));
  }

  private static String key(Provider provider, String modelId) {
    return provider.name() + "\u0000" + modelId;
  }

  private List<ModelOption> listFor(Provider provider) {
    return switch (provider) {
      case OPENAI -> catalog.getOpenai();
      case GOOGLE -> catalog.getGoogle();
      case CLAUDE -> catalog.getClaude();
      case OPENROUTER -> catalog.getOpenrouter();
      case BRAVE_SEARCH -> List.of();
    };
  }

  /**
   * Parses an OpenRouter-style USD-per-token price string and returns USD per 1,000,000 tokens.
   * Returns {@code null} for blank/unparsable input.
   */
  private static Double parseUsdPerMillion(String usdPerToken) {
    if (usdPerToken == null || usdPerToken.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(usdPerToken.trim()) * TOKENS_PER_MILLION;
    } catch (NumberFormatException e) {
      log.debug("Unparseable price '{}': {}", usdPerToken, e.getMessage());
      return null;
    }
  }
}
