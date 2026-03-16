package com.rft.chat_sribiddi.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
        new ArrayList<>(List.of(new ModelOption("openrouter/auto", "OpenRouter Auto"))));
    service = new ModelCatalogService(properties, modelFetchService);
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
    List<ModelOption> liveModels = List.of(new ModelOption("openai/gpt-4o", "GPT-4o"));
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
}
