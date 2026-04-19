package com.rft.chat_sribiddi.billing;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.List;
import java.util.Optional;
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
    Optional<ModelOption> match =
        listFor(provider).stream().filter(opt -> modelId.equals(opt.getId())).findFirst();
    if (match.isPresent()) {
      Double prompt = parseUsdPerMillion(match.get().getPromptPrice());
      Double completion = parseUsdPerMillion(match.get().getCompletionPrice());
      if (prompt != null && completion != null) {
        return Optional.of(new Pricing(prompt, completion));
      }
    }
    // OpenRouter free-model convention: ids ending with ':free' are zero-cost.
    if (provider == Provider.OPENROUTER && modelId.endsWith(":free")) {
      return Optional.of(new Pricing(0.0, 0.0));
    }
    return Optional.empty();
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
