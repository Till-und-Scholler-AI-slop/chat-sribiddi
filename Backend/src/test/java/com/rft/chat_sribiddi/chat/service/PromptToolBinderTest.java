package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

@ExtendWith(MockitoExtension.class)
class PromptToolBinderTest {

  @Mock private ChatToolRepository chatToolRepository;
  @Mock private ApiKeyService apiKeyService;
  @Mock private HttpClient webSearchHttpClient;
  @Mock private ToolRegistry toolRegistry;
  @Mock private CalculatorTool calculatorTool;
  @Mock private UrlFetcherTool urlFetcherTool;
  @Mock private PythonInterpreterTool pythonInterpreterTool;
  @Mock private EntitlementService entitlementService;
  @Mock private ChatClient.ChatClientRequestSpec promptSpec;

  private ChatProperties chatProperties;
  private PromptToolBinder binder;
  private UUID chatId;

  @BeforeEach
  void setUp() {
    chatProperties = new ChatProperties();
    binder =
        new PromptToolBinder(
            chatProperties,
            chatToolRepository,
            apiKeyService,
            new WebSearchProperties(),
            webSearchHttpClient,
            toolRegistry,
            calculatorTool,
            urlFetcherTool,
            pythonInterpreterTool,
            new CurrentDateTimeTool(),
            entitlementService);
    chatId = UUID.randomUUID();
  }

  @Test
  void supportsTools_usesConfiguredProviderModelWildcards() {
    chatProperties.getToolDisabledModelPatterns().add("google:gemma-*");
    chatProperties.getToolDisabledModelPatterns().add("openrouter:*venice*uncensored*");

    assertThat(binder.supportsTools(RequestDTO.Provider.GOOGLE, "gemma-3-27b-it")).isFalse();
    assertThat(binder.supportsTools(RequestDTO.Provider.GOOGLE, "gemini-2.5-flash")).isTrue();
    assertThat(binder.supportsTools(RequestDTO.Provider.OPENROUTER, "venice/uncensored"))
        .isFalse();
  }

  @Test
  void bindTools_wiresEnabledCustomToolDirectly() {
    ChatTool customTool = mock(ChatTool.class);
    when(customTool.getName()).thenReturn("custom-tool");
    when(toolRegistry.getAll()).thenReturn(List.of(customTool));
    when(entitlementService.get("user-123")).thenReturn(entitlements("*"));
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of(enabledTool("custom-tool")));

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    verify(promptSpec).tools(customTool);
  }

  @Test
  void bindTools_skipsToolsDeniedByEntitlements() {
    ChatTool customTool = mock(ChatTool.class);
    when(customTool.getName()).thenReturn("custom-tool");
    when(toolRegistry.getAll()).thenReturn(List.of(customTool));
    when(entitlementService.get("user-123")).thenReturn(entitlements("calculator"));

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    verify(promptSpec, never()).tools(any());
    verify(chatToolRepository, never()).findByChatIdAndToolName(any(), anyString());
    verify(chatToolRepository, never()).findByChatId(any());
  }

  @Test
  void bindTools_usesServerWebSearchKeyWhenUserKeyMissing() {
    ChatTool braveSearchTool = mock(ChatTool.class);
    when(braveSearchTool.getName()).thenReturn("brave-search");
    when(toolRegistry.getAll()).thenReturn(List.of(braveSearchTool));
    when(entitlementService.get("user-123")).thenReturn(entitlements("brave-search"));
    when(apiKeyService.getKey("user-123", RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.empty());
    WebSearchProperties properties = new WebSearchProperties();
    properties.setApiKey("server-brave-key");
    binder =
        new PromptToolBinder(
            chatProperties,
            chatToolRepository,
            apiKeyService,
            properties,
            webSearchHttpClient,
            toolRegistry,
            calculatorTool,
            urlFetcherTool,
            pythonInterpreterTool,
            new CurrentDateTimeTool(),
            entitlementService);
    AtomicReference<Object> boundTool = new AtomicReference<>();
    doAnswer(
            invocation -> {
              boundTool.set(invocation.getArgument(0));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        true,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundTool.get()).isNotNull();
    assertThat(boundTool.get().getClass().getSimpleName()).isEqualTo("LoggedBraveSearchTool");
  }

  private static ChatToolEntity enabledTool(String toolName) {
    ChatToolEntity entity = new ChatToolEntity();
    entity.setToolName(toolName);
    entity.setEnabled(true);
    return entity;
  }

  private static Entitlements entitlements(String allowedTool) {
    return new Entitlements(
        Plan.FREE,
        5,
        5_242_880,
        20_971_520,
        20,
        Set.of(allowedTool),
        100,
        1,
        null,
        null);
  }
}
