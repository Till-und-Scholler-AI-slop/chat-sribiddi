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
import com.rft.chat_sribiddi.connectors.ConnectorAccountService;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.AgentPlanTool;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.ChartGeneratorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialProperties;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialResolver;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

@ExtendWith(MockitoExtension.class)
class PromptToolBinderTest {

  @Mock private ChatToolRepository chatToolRepository;
  @Mock private ApiKeyService apiKeyService;
  @Mock private HttpClient webSearchHttpClient;
  @Mock private ToolRegistry toolRegistry;
  @Mock private CalculatorTool calculatorTool;
  @Mock private UrlFetcherTool urlFetcherTool;
  @Mock private PythonInterpreterTool pythonInterpreterTool;
  @Mock private ChartGeneratorTool chartGeneratorTool;
  @Mock private AgentPlanTool agentPlanTool;
  @Mock private ToolCredentialResolver toolCredentialResolver;
  @Mock private EntitlementService entitlementService;
  @Mock private ConnectorAccountService connectorAccountService;
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
            chartGeneratorTool,
            agentPlanTool,
            toolCredentialResolver,
            new ToolCredentialProperties(),
            entitlementService,
            connectorAccountService);
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
    ChatTool customTool = new CustomAnnotatedTool();
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

    ArgumentCaptor<Consumer<ChatClient.ToolSpec>> toolsCaptor =
        ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).tools(toolsCaptor.capture());
    assertThat(captureCallbacks(toolsCaptor.getValue()))
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("run"));
  }

  @Test
  void bindTools_passesConsentArgsThroughSpringAiToolContext() {
    ChatTool customTool = new CustomAnnotatedTool();
    when(toolRegistry.getAll()).thenReturn(List.of(customTool));
    when(entitlementService.get("user-123")).thenReturn(entitlements("*"));
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of(enabledTool("custom-tool")));

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(
            new RequestDTO.ToolConsent(
                "custom-tool", "LOW", Map.of("approvedHost", "example.com"))),
        new ToolExecutionCollector(),
        promptSpec);

    ArgumentCaptor<Consumer<ChatClient.ToolSpec>> toolsCaptor =
        ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).tools(toolsCaptor.capture());

    CapturedToolSpec captured = captureToolSpec(toolsCaptor.getValue());
    assertThat(captured.callbacks())
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("run"));
    assertThat(captured.context())
        .containsEntry(
            PromptToolBinder.TOOL_CONSENT_CONTEXT_KEY,
            Map.of("custom-tool", Map.of("approvedHost", "example.com")));
  }

  @Test
  void bindTools_attachesEmptyConsentContextWhenNoConsentArgsProvided() {
    ChatTool customTool = new CustomAnnotatedTool();
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

    ArgumentCaptor<Consumer<ChatClient.ToolSpec>> toolsCaptor =
        ArgumentCaptor.forClass(Consumer.class);
    verify(promptSpec).tools(toolsCaptor.capture());

    // Spring AI 2.0.0-M8's MethodToolCallback rejects a tool that declares a ToolContext
    // parameter (e.g. UrlFetcherTool#fetchUrl) when the ToolContext map is empty, so the consent
    // context must always be attached — even with an empty inner map — for the tool to run.
    CapturedToolSpec captured = captureToolSpec(toolsCaptor.getValue());
    assertThat(captured.context())
        .containsEntry(PromptToolBinder.TOOL_CONSENT_CONTEXT_KEY, Map.of());
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

    verify(promptSpec, never()).tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());
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
            chartGeneratorTool,
            agentPlanTool,
            toolCredentialResolver,
            new ToolCredentialProperties(),
            entitlementService,
            connectorAccountService);
    AtomicReference<ToolCallback[]> boundCallbacks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Consumer<ChatClient.ToolSpec> consumer = invocation.getArgument(0);
              boundCallbacks.set(captureCallbacks(consumer));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        true,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundCallbacks.get()).isNotNull();
    assertThat(boundCallbacks.get())
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("searchWeb"));
  }

  @Test
  void bindTools_allowsBraveSearchWhenUserKeyExistsEvenIfPlanDeniesTool() {
    ChatTool braveSearchTool = mock(ChatTool.class);
    when(braveSearchTool.getName()).thenReturn("brave-search");
    when(toolRegistry.getAll()).thenReturn(List.of(braveSearchTool));
    when(entitlementService.get("user-123")).thenReturn(entitlements("calculator"));
    when(apiKeyService.getKey("user-123", RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));
    AtomicReference<ToolCallback[]> boundCallbacks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Consumer<ChatClient.ToolSpec> consumer = invocation.getArgument(0);
              boundCallbacks.set(captureCallbacks(consumer));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        true,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundCallbacks.get()).isNotNull();
    assertThat(boundCallbacks.get())
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("searchWeb"));
  }

  @Test
  void bindTools_allowsUrlFetcherWhenUserHasAnyProviderKey() {
    ChatTool urlFetcher = mock(ChatTool.class);
    when(urlFetcher.getName()).thenReturn("url-fetcher");
    when(urlFetcher.requiresPerCallConsent()).thenReturn(false);
    when(toolRegistry.getAll()).thenReturn(List.of(urlFetcher));
    // Free tier — no url-fetcher in entitlement.
    when(entitlementService.get("user-123")).thenReturn(entitlements("calculator"));
    // Iterate over providers in declared order; OPENAI returns false (skipped), then BRAVE_SEARCH
    // returns true. Stubs for the providers between them are unnecessary (never called) so we
    // use lenient() for those.
    when(apiKeyService.hasKey("user-123", RequestDTO.Provider.OPENAI)).thenReturn(false);
    when(apiKeyService.hasKey("user-123", RequestDTO.Provider.BRAVE_SEARCH)).thenReturn(true);
    when(apiKeyService.getKey("user-123", RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));

    AtomicReference<ToolCallback[]> boundCallbacks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Consumer<ChatClient.ToolSpec> consumer = invocation.getArgument(0);
              boundCallbacks.set(captureCallbacks(consumer));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundCallbacks.get())
        .isNotNull()
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("fetchUrl"));
  }

  @Test
  void bindTools_skipsUrlFetcherForFreeUserWithoutProviderKeys() {
    ChatTool urlFetcher = mock(ChatTool.class);
    when(urlFetcher.getName()).thenReturn("url-fetcher");
    when(toolRegistry.getAll()).thenReturn(List.of(urlFetcher));
    when(entitlementService.get("user-123")).thenReturn(entitlements("calculator"));
    for (RequestDTO.Provider provider : RequestDTO.Provider.values()) {
      when(apiKeyService.hasKey("user-123", provider)).thenReturn(false);
    }

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    verify(promptSpec, never()).tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());
  }

  @Test
  void bindTools_skipsUrlFetcherWhenStoredKeyIsBlank() {
    ChatTool urlFetcher = mock(ChatTool.class);
    when(urlFetcher.getName()).thenReturn("url-fetcher");
    when(toolRegistry.getAll()).thenReturn(List.of(urlFetcher));
    when(entitlementService.get("user-123")).thenReturn(entitlements("calculator"));
    when(apiKeyService.hasKey("user-123", RequestDTO.Provider.OPENAI)).thenReturn(true);
    when(apiKeyService.getKey("user-123", RequestDTO.Provider.OPENAI)).thenReturn(Optional.of("   "));
    for (RequestDTO.Provider provider : RequestDTO.Provider.values()) {
      if (provider != RequestDTO.Provider.OPENAI) {
        when(apiKeyService.hasKey("user-123", provider)).thenReturn(false);
      }
    }

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    verify(promptSpec, never()).tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());
  }

  @Test
  void bindTools_urlFetcherEnabledByDefaultPerChat() {
    ChatTool urlFetcher = mock(ChatTool.class);
    when(urlFetcher.getName()).thenReturn("url-fetcher");
    when(urlFetcher.requiresPerCallConsent()).thenReturn(false);
    when(toolRegistry.getAll()).thenReturn(List.of(urlFetcher));
    when(entitlementService.get("user-123")).thenReturn(entitlements("*"));
    // No ChatToolEntity — falls through to the default-true list.
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of());

    AtomicReference<ToolCallback[]> boundCallbacks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Consumer<ChatClient.ToolSpec> consumer = invocation.getArgument(0);
              boundCallbacks.set(captureCallbacks(consumer));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundCallbacks.get())
        .isNotNull()
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("fetchUrl"));
  }

  @Test
  void bindTools_urlFetcherBindsWithoutConsentArgs() {
    ChatTool urlFetcher = mock(ChatTool.class);
    when(urlFetcher.getName()).thenReturn("url-fetcher");
    // Even if the tool claims HIGH risk, the binder should NOT skip it when the tool itself
    // overrides requiresPerCallConsent() to false. This test pins that contract.
    when(urlFetcher.requiresPerCallConsent()).thenReturn(false);
    when(toolRegistry.getAll()).thenReturn(List.of(urlFetcher));
    when(entitlementService.get("user-123")).thenReturn(entitlements("*"));
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of());

    AtomicReference<ToolCallback[]> boundCallbacks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Consumer<ChatClient.ToolSpec> consumer = invocation.getArgument(0);
              boundCallbacks.set(captureCallbacks(consumer));
              return promptSpec;
            })
        .when(promptSpec)
        .tools(ArgumentMatchers.<Consumer<ChatClient.ToolSpec>>any());

    binder.bindTools(
        chatId,
        "user-123",
        RequestDTO.Provider.OPENAI,
        "gpt-5.2",
        false,
        List.of(),
        new ToolExecutionCollector(),
        promptSpec);

    assertThat(boundCallbacks.get())
        .isNotNull()
        .singleElement()
        .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("fetchUrl"));
  }

  private static final class CustomAnnotatedTool implements ChatTool {

    @Override
    public String getName() {
      return "custom-tool";
    }

    @Override
    public String getDisplayName() {
      return "Custom Tool";
    }

    @Override
    public ToolResult execute(String userId, Map<String, Object> parameters) {
      return ToolResult.success("ok");
    }

    @Tool(description = "Run the custom test tool.")
    public String run() {
      return "ok";
    }
  }

  private static ToolCallback[] captureCallbacks(Consumer<ChatClient.ToolSpec> consumer) {
    return captureToolSpec(consumer).callbacks();
  }

  private static CapturedToolSpec captureToolSpec(Consumer<ChatClient.ToolSpec> consumer) {
    ChatClient.ToolSpec toolSpec = mock(ChatClient.ToolSpec.class);
    AtomicReference<ToolCallback[]> callbacksRef = new AtomicReference<>();
    Map<String, Object> context = new java.util.LinkedHashMap<>();
    when(toolSpec.callbacks((ToolCallback[]) any(ToolCallback[].class)))
        .thenAnswer(
            invocation -> {
              Object[] arguments = invocation.getArguments();
              ToolCallback[] callbacks = new ToolCallback[arguments.length];
              for (int i = 0; i < arguments.length; i++) {
                callbacks[i] = (ToolCallback) arguments[i];
              }
              callbacksRef.set(callbacks);
              return toolSpec;
            });
    lenient()
        .when(toolSpec.context(anyString(), any()))
        .thenAnswer(
            invocation -> {
              context.put(invocation.getArgument(0), invocation.getArgument(1));
              return toolSpec;
            });
    consumer.accept(toolSpec);
    return new CapturedToolSpec(callbacksRef.get(), Map.copyOf(context));
  }

  private record CapturedToolSpec(ToolCallback[] callbacks, Map<String, Object> context) {}

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
