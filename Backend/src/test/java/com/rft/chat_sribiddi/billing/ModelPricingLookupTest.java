package com.rft.chat_sribiddi.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelPricingLookupTest {

  private ModelCatalogProperties catalog;
  private ModelPricingLookup lookup;

  @BeforeEach
  void setUp() {
    catalog = new ModelCatalogProperties();
    lookup = new ModelPricingLookup(catalog);
  }

  private static ModelOption opt(String id, String prompt, String completion) {
    ModelOption o = new ModelOption();
    o.setId(id);
    o.setPromptPrice(prompt);
    o.setCompletionPrice(completion);
    return o;
  }

  @Test
  void find_returnsZeroForOpenRouterFreeSuffixWhenCatalogEmpty() {
    Optional<ModelPricingLookup.Pricing> price =
        lookup.find(Provider.OPENROUTER, "meta-llama/llama-3.1-8b-instruct:free");

    assertThat(price).isPresent();
    assertThat(price.get().inputUsdPerMillion()).isZero();
    assertThat(price.get().outputUsdPerMillion()).isZero();
  }

  @Test
  void find_returnsZeroForOpenRouterFreeRouterWhenCatalogEmpty() {
    Optional<ModelPricingLookup.Pricing> price =
        lookup.find(Provider.OPENROUTER, "openrouter/free");

    assertThat(price).isPresent();
    assertThat(price.get().inputUsdPerMillion()).isZero();
    assertThat(price.get().outputUsdPerMillion()).isZero();
  }

  @Test
  void find_returnsEmptyForUnknownModel() {
    Optional<ModelPricingLookup.Pricing> price = lookup.find(Provider.OPENAI, "mystery-model");

    assertThat(price).isEmpty();
  }

  @Test
  void find_parsesPerTokenPricingIntoUsdPerMillion() {
    // OpenRouter returns prices as USD per token (e.g. "0.00000020" = $0.20 / 1M).
    catalog.setOpenrouter(List.of(opt("openai/gpt-4o-mini", "0.00000015", "0.00000060")));

    Optional<ModelPricingLookup.Pricing> price =
        lookup.find(Provider.OPENROUTER, "openai/gpt-4o-mini");

    assertThat(price).isPresent();
    assertThat(price.get().inputUsdPerMillion()).isEqualTo(0.15);
    assertThat(price.get().outputUsdPerMillion()).isEqualTo(0.60);
  }

  @Test
  void remember_usesLiveCatalogPricingForLaterLookups() {
    lookup.remember(
        Provider.OPENROUTER,
        List.of(opt("anthropic/claude-sonnet-4", "0.000003", "0.000015")));

    Optional<ModelPricingLookup.Pricing> price =
        lookup.find(Provider.OPENROUTER, "anthropic/claude-sonnet-4");

    assertThat(price).isPresent();
    assertThat(price.get().inputUsdPerMillion()).isEqualTo(3.0);
    assertThat(price.get().outputUsdPerMillion()).isEqualTo(15.0);
  }

  @Test
  void find_returnsZeroForExplicitlyFreeOpenRouterModel() {
    catalog.setOpenrouter(List.of(opt("some/free-model", "0", "0")));

    Optional<ModelPricingLookup.Pricing> price =
        lookup.find(Provider.OPENROUTER, "some/free-model");

    assertThat(price).isPresent();
    assertThat(price.get().inputUsdPerMillion()).isZero();
    assertThat(price.get().outputUsdPerMillion()).isZero();
  }

  @Test
  void find_returnsEmptyForBlankInput() {
    assertThat(lookup.find(Provider.OPENAI, "")).isEmpty();
    assertThat(lookup.find(Provider.OPENAI, null)).isEmpty();
    assertThat(lookup.find(null, "foo")).isEmpty();
  }
}
