package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.billing.ModelPricingLookup;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

  @Mock private ModelFetchService modelFetchService;

  private ModelCatalogService service;
  private ModelCatalogProperties properties;
  private ModelPricingLookup pricingLookup;

  @BeforeEach
  void setUp() {
    properties = new ModelCatalogProperties();
    properties.setOpenai(
        new ArrayList<>(List.of(new ModelOption("gpt-4.1-2025-04-14", "GPT-4.1"))));
    properties.setGoogle(
        new ArrayList<>(
            List.of(new ModelOption("gemini-2.5-pro-preview-05-06", "Gemini 2.5 Pro"))));
    properties.setClaude(
        new ArrayList<>(List.of(new ModelOption("claude-sonnet-4-20250514", "Claude Sonnet 4"))));
    properties.setOpenrouter(
        new ArrayList<>(List.of(new ModelOption("openrouter/free", "OpenRouter Free"))));
    pricingLookup = new ModelPricingLookup(properties);
    service = new ModelCatalogService(properties, modelFetchService, pricingLookup);
  }

  // --- OpenAI ---

  @Test
  void listOpenAiModels_returnsLiveResults_whenApiKeyProvidedAndFetchSucceeds() {
    List<ModelOption> liveModels = List.of(new ModelOption("gpt-4o", "GPT-4o"));
    when(modelFetchService.fetchOpenAiModels("sk-key")).thenReturn(liveModels);

    List<ModelOption> result = service.listOpenAiModels("sk-key");

    assertThat(result).isEqualTo(liveModels);
    verify(modelFetchService).fetchOpenAiModels("sk-key");
  }

  @Test
  void listOpenAiModels_fallsBackToStatic_whenFetchReturnsEmpty() {
    when(modelFetchService.fetchOpenAiModels("sk-key")).thenReturn(List.of());

    List<ModelOption> result = service.listOpenAiModels("sk-key");

    assertThat(result).isEqualTo(properties.getOpenai());
  }

  @Test
  void listOpenAiModels_doesNotFetch_whenApiKeyIsNull() {
    List<ModelOption> result = service.listOpenAiModels(null);

    assertThat(result).isEqualTo(properties.getOpenai());
    verifyNoInteractions(modelFetchService);
  }

  @Test
  void listOpenAiModels_doesNotFetch_whenApiKeyIsBlank() {
    List<ModelOption> result = service.listOpenAiModels("   ");

    assertThat(result).isEqualTo(properties.getOpenai());
    verifyNoInteractions(modelFetchService);
  }

  // --- Google ---

  @Test
  void listGoogleModels_returnsLiveResults_whenApiKeyProvidedAndFetchSucceeds() {
    List<ModelOption> liveModels = List.of(new ModelOption("gemini-2.0-flash", "Gemini 2.0 Flash"));
    when(modelFetchService.fetchGoogleModels("goog-key")).thenReturn(liveModels);

    List<ModelOption> result = service.listGoogleModels("goog-key");

    assertThat(result).isEqualTo(liveModels);
  }

  @Test
  void listGoogleModels_fallsBackToStatic_whenFetchReturnsEmpty() {
    when(modelFetchService.fetchGoogleModels("goog-key")).thenReturn(List.of());

    List<ModelOption> result = service.listGoogleModels("goog-key");

    assertThat(result).isEqualTo(properties.getGoogle());
  }

  @Test
  void listGoogleModels_doesNotFetch_whenApiKeyIsNull() {
    List<ModelOption> result = service.listGoogleModels(null);

    assertThat(result).isEqualTo(properties.getGoogle());
    verifyNoInteractions(modelFetchService);
  }

  // --- Claude ---

  @Test
  void listClaudeModels_returnsLiveResults_whenApiKeyProvidedAndFetchSucceeds() {
    List<ModelOption> liveModels = List.of(new ModelOption("claude-3-5-sonnet", "Claude 3.5 Sonnet"));
    when(modelFetchService.fetchClaudeModels("anth-key")).thenReturn(liveModels);

    List<ModelOption> result = service.listClaudeModels("anth-key");

    assertThat(result).isEqualTo(liveModels);
  }

  @Test
  void listClaudeModels_fallsBackToStatic_whenFetchReturnsEmpty() {
    when(modelFetchService.fetchClaudeModels("anth-key")).thenReturn(List.of());

    List<ModelOption> result = service.listClaudeModels("anth-key");

    assertThat(result).isEqualTo(properties.getClaude());
  }

  @Test
  void listClaudeModels_doesNotFetch_whenApiKeyIsBlank() {
    List<ModelOption> result = service.listClaudeModels("");

    assertThat(result).isEqualTo(properties.getClaude());
    verifyNoInteractions(modelFetchService);
  }

  // --- OpenRouter ---

  @Test
  void listOpenRouterModels_returnsLiveResults_whenApiKeyProvidedAndFetchSucceeds() {
    List<ModelOption> liveModels = List.of(liveOption("openai/gpt-4o", "GPT-4o", "0.0000025", "0.000010"));
    when(modelFetchService.fetchOpenRouterModels("or-key")).thenReturn(liveModels);

    List<ModelOption> result = service.listOpenRouterModels("or-key");

    assertThat(result)
        .extracting(ModelOption::getId)
        .containsExactly("openrouter/free", "openai/gpt-4o");
    assertThat(pricingLookup.find(com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider.OPENROUTER, "openai/gpt-4o"))
        .isPresent()
        .get()
        .extracting(ModelPricingLookup.Pricing::inputUsdPerMillion)
        .isEqualTo(2.5);
  }

  @Test
  void listOpenRouterModels_doesNotDuplicateFreeRouterWhenLiveCatalogContainsIt() {
    List<ModelOption> liveModels =
        List.of(
            liveOption("openrouter/free", "OpenRouter Free", "0", "0"),
            liveOption("openai/gpt-4o", "GPT-4o", "0.0000025", "0.000010"));
    when(modelFetchService.fetchOpenRouterModels("or-key")).thenReturn(liveModels);

    List<ModelOption> result = service.listOpenRouterModels("or-key");

    assertThat(result).isEqualTo(liveModels);
  }

  @Test
  void listOpenRouterModels_fallsBackToStatic_whenFetchReturnsEmpty() {
    when(modelFetchService.fetchOpenRouterModels("or-key")).thenReturn(List.of());

    List<ModelOption> result = service.listOpenRouterModels("or-key");

    assertThat(result).isEqualTo(properties.getOpenrouter());
  }

  @Test
  void listOpenRouterModels_doesNotFetch_whenApiKeyIsBlank() {
    List<ModelOption> result = service.listOpenRouterModels("   ");

    assertThat(result).isEqualTo(properties.getOpenrouter());
    verifyNoInteractions(modelFetchService);
  }

  @Test
  void compactModels_prefersConfiguredFeaturedIdsAndFillsToThree() {
    properties.getFeatured().setOpenai(List.of("gpt-5.2-mini", "missing-id"));
    ModelOption alpha = new ModelOption("alpha", "Alpha");
    ModelOption featured = new ModelOption("gpt-5.2-mini", "GPT-5.2 Mini");
    ModelOption beta = new ModelOption("beta", "Beta");
    ModelOption zeta = new ModelOption("zeta", "Zeta");

    List<ModelOption> result =
        service.compactModels(Provider.OPENAI, List.of(zeta, beta, featured, alpha));

    assertThat(result).containsExactly(featured, alpha, beta);
  }

  @Test
  void compactModels_returnsEveryUsableModelWhenCatalogHasFewerThanThree() {
    ModelOption one = new ModelOption("one", "One");
    ModelOption two = new ModelOption("two", "Two");

    List<ModelOption> result = service.compactModels(Provider.OPENROUTER, List.of(two, one));

    assertThat(result).containsExactly(one, two);
  }

  private static ModelOption liveOption(
      String id, String label, String promptPrice, String completionPrice) {
    ModelOption option = new ModelOption(id, label);
    option.setPromptPrice(promptPrice);
    option.setCompletionPrice(completionPrice);
    return option;
  }
}
