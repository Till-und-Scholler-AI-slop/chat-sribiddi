package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.ManagedApiKeyProperties;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.billing.UsageEventService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ToolCallLogRepository toolCallLogRepository;
  @Mock private ChatToolRepository chatToolRepository;
  @Mock private ApiKeyService apiKeyService;
  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private com.rft.chat_sribiddi.auth.UserSettingsService userSettingsService;
  @Mock private EntitlementService entitlementService;
  @Mock private UsageEventService usageEventService;
  @Mock private ChatMetrics chatMetrics;
  @Mock private HttpClient webSearchHttpClient;
  @Mock private ToolRegistry toolRegistry;
  @Mock private CalculatorTool calculatorTool;
  @Mock private UrlFetcherTool urlFetcherTool;
  @Mock private PythonInterpreterTool pythonInterpreterTool;

  @Mock private ChatClient chatClient;
  @Mock private ChatClient.ChatClientRequestSpec requestSpec;
  @Mock private ChatClient.CallResponseSpec callResponseSpec;
  @Mock private ChatClient.StreamResponseSpec streamResponseSpec;

  private ChatService chatService;
  private UUID chatId;
  private String userId;

  private ChatService buildChatService(ChatProperties chatProperties) {
    ManagedApiKeyProperties managedKeys = new ManagedApiKeyProperties();
    managedKeys.setOpenai("srv-openai");
    managedKeys.setGoogle("srv-google");
    managedKeys.setClaude("srv-claude");
    managedKeys.setOpenrouter("srv-openrouter");
    BillingModeResolver billingModeResolver =
        new BillingModeResolver(apiKeyService, entitlementService, managedKeys);
    MediaValidationService mediaValidationService = new MediaValidationService(chatProperties);
    PromptToolBinder promptToolBinder =
        new PromptToolBinder(
            chatProperties,
            chatToolRepository,
            apiKeyService,
            new com.rft.chat_sribiddi.websearch.WebSearchProperties(),
            webSearchHttpClient,
            toolRegistry,
            calculatorTool,
            urlFetcherTool,
            pythonInterpreterTool,
            new com.rft.chat_sribiddi.tools.CurrentDateTimeTool(),
            entitlementService);
    PromptMessageBuilder promptMessageBuilder =
        new PromptMessageBuilder(messageRepository, chatProperties, mediaValidationService);
    ChatUsageRecorder chatUsageRecorder =
        new ChatUsageRecorder(entitlementService, usageEventService, chatMetrics);
    ToolCallLogRecorder toolCallLogRecorder = new ToolCallLogRecorder(toolCallLogRepository);
    ChatStreamSubscriber chatStreamSubscriber =
        new ChatStreamSubscriber(
            chatRepository,
            messageRepository,
            chatUsageRecorder,
            toolCallLogRecorder,
            chatMetrics);
    return new ChatService(chatRepository, messageRepository, toolCallLogRepository, chatToolRepository,
        providerClientFactory, chatProperties, mediaValidationService, userSettingsService,
        promptToolBinder, promptMessageBuilder, chatUsageRecorder, toolCallLogRecorder,
        chatStreamSubscriber, billingModeResolver, entitlementService);
  }

  @BeforeEach
  void setUp() {
    chatService = buildChatService(new ChatProperties());
    chatId = UUID.randomUUID();
    userId = "user-123";

    lenient().when(userSettingsService.getCustomInstructions(any())).thenReturn(java.util.Optional.empty());
    lenient()
        .when(entitlementService.resolveBillingMode(anyString()))
        .thenReturn(BillingMode.BYOK);
    lenient().when(entitlementService.get(anyString())).thenReturn(entitlements(Plan.FREE));
    lenient().when(chatToolRepository.findByChatId(any())).thenReturn(List.of());
    lenient().when(toolRegistry.getAll()).thenReturn(List.of());
    lenient()
        .when(apiKeyService.getKey(eq(userId), any(RequestDTO.Provider.class)))
        .thenAnswer(
            invocation -> {
              RequestDTO.Provider provider = invocation.getArgument(1);
              return Optional.of(
                  switch (provider) {
                    case OPENAI -> "key";
                    case GOOGLE -> "goog-key";
                    case CLAUDE -> "claude-key";
                    case OPENROUTER -> "or-key";
                    case BRAVE_SEARCH -> "brave-key";
                  });
            });

    AtomicLong ids = new AtomicLong(100);
    lenient().when(messageRepository.save(any(MessageEntity.class)))
        .thenAnswer(
            invocation -> {
              MessageEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                entity.setId(ids.getAndIncrement());
              }
              return entity;
            });
  }

  @Test
  void sendMessage_resolvesStoredKey_andPersistsMessages() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder()
                .model("gpt-5.2-2025-12-11")
                .build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))));
    when(callResponseSpec.chatResponse()).thenReturn(response);

    SendMessageResponseDTO result = chatService.sendMessage(chatId, userId, request);

    assertThat(result.message()).isEqualTo("pong");

    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    assertThat(savedMessages.getAllValues())
        .extracting(MessageEntity::getRole)
        .containsExactly(Role.USER, Role.ASSISTANT);
  }

  @Test
  void sendMessage_usesTierContextLimitWhenBuildingPrompt() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(entitlementService.get(userId))
        .thenReturn(
            new Entitlements(
                Plan.FREE, 5, 5_242_880, 20_971_520, 1, Set.of("*"), 100, 1, 0.0, 0.0));
    setupSendMessageMocks(chat, "key");

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findByChatIdOrderByCreatedAtDesc(eq(chatId), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
  }

  @Test
  void listChats_usesLimitAndOffsetPageable() {
    ChatProperties properties = new ChatProperties();
    properties.setChatPageSize(25);
    chatService = buildChatService(properties);
    when(chatRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
        .thenReturn(List.of());

    chatService.listChats(userId, 10, 20);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(chatRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    assertThat(pageable.getValue().getOffset()).isEqualTo(20);
  }

  @Test
  void listMessages_clampsLimitUsesOffsetAndReturnsChronologicalOrder() {
    ChatProperties properties = new ChatProperties();
    properties.setMessagePageSize(2);
    chatService = buildChatService(properties);
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    MessageEntity newest = messageWithId(2L);
    MessageEntity oldest = messageWithId(1L);
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(newest, oldest));

    List<MessageEntity> result = chatService.listMessages(chatId, userId, 10, 3);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findByChatIdOrderByCreatedAtDesc(eq(chatId), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
    assertThat(pageable.getValue().getOffset()).isEqualTo(3);
    assertThat(result).extracting(MessageEntity::getId).containsExactly(1L, 2L);
  }

  @Test
  void streamSetupFailure_closesClientHandle() throws Exception {
    AutoCloseable closeable = mock(AutoCloseable.class);
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", null, null, null);
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of());
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, closeable));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", "key"))
        .thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> chatService.streamMessage(chatId, userId, request))
        .isInstanceOf(RuntimeException.class);
    verify(closeable).close();
  }

  @Test
  void sendMessage_freeUserWithPremiumModel_throwsPlanRestriction() {
    RequestDTO request =
        new RequestDTO(
            "hi", RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.AUTO);
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENROUTER)).thenReturn(Optional.empty());
    doThrow(
            new PlanRestrictionException(
                PlanRestrictionException.Reason.MODEL_NOT_ALLOWED, Plan.FREE, "Premium model"))
        .when(entitlementService)
        .assertCanUseModel(
            userId, RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", BillingMode.MANAGED);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOfSatisfying(
            PlanRestrictionException.class,
            ex ->
                assertThat(ex.getReason())
                    .isEqualTo(PlanRestrictionException.Reason.MODEL_NOT_ALLOWED));

    verify(entitlementService, never()).assertWithinDailyCap(anyString());
    verifyNoInteractions(providerClientFactory);
    verifyNoInteractions(usageEventService);
  }

  @Test
  void sendMessage_freeUserOverDailyCap_throwsPlanRestriction() {
    RequestDTO request =
        new RequestDTO(
            "hi", RequestDTO.Provider.OPENROUTER, "openai/gpt-4.1-mini", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.MANAGED);
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENROUTER)).thenReturn(Optional.empty());
    doThrow(
            new PlanRestrictionException(
                PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED,
                Plan.FREE,
                "Daily cap exceeded"))
        .when(entitlementService)
        .assertWithinDailyCap(userId);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOfSatisfying(
            PlanRestrictionException.class,
            ex ->
                assertThat(ex.getReason())
                    .isEqualTo(PlanRestrictionException.Reason.DAILY_CAP_EXCEEDED));

    verify(entitlementService)
        .assertCanUseModel(
            userId, RequestDTO.Provider.OPENROUTER, "openai/gpt-4.1-mini", BillingMode.MANAGED);
    verifyNoInteractions(providerClientFactory);
    verifyNoInteractions(usageEventService);
    verify(entitlementService, never()).incrementCounter(anyString());
  }

  @Test
  void sendMessage_persistsToolCallLogsForAssistantMessage() throws Exception {
    RequestDTO request =
        new RequestDTO("what time is it?", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder()
                .model("gpt-5.2")
                .build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    // Provide current-datetime in registry so wireTools() wires it
    when(toolRegistry.getAll()).thenReturn(List.of(new com.rft.chat_sribiddi.tools.CurrentDateTimeTool()));

    AtomicReference<Object> dateTimeToolRef = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object[] args = invocation.getArguments();
              if (args.length > 0 && args[0] != null) {
                dateTimeToolRef.set(args[0]);
              }
              return null;
            })
        .when(requestSpec)
        .tools(any());

    when(callResponseSpec.chatResponse())
        .thenAnswer(
            invocation -> {
              invokeNoArgTool(dateTimeToolRef.get(), "getCurrentDateTime");
              return new ChatResponse(List.of(new Generation(new AssistantMessage("Current time noted."))));
            });

    chatService.sendMessage(chatId, userId, request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ToolCallLogEntity>> logsCaptor = ArgumentCaptor.forClass(List.class);
    verify(toolCallLogRepository).saveAll(logsCaptor.capture());
    assertThat(logsCaptor.getValue()).hasSize(1);
    ToolCallLogEntity log = logsCaptor.getValue().getFirst();
    assertThat(log.getToolName()).isEqualTo("CurrentDateTimeTool.getCurrentDateTime");
    assertThat(log.getInputPayload()).isEqualTo("{}");
    assertThat(log.getOutputPayload()).isNotBlank();
    assertThat(log.getDurationMs()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void sendMessage_toolDisabledModelSkipsToolDefinitions() {
    ChatProperties properties = new ChatProperties();
    properties.getToolDisabledModelPatterns().add("google:gemma-*");
    chatService = buildChatService(properties);
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "goog-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", "goog-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gemma-3-27b-it").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

    chatService.sendMessage(chatId, userId, request);

    verify(requestSpec, never()).tools(any());
  }

  @Test
  void sendMessage_highRiskToolWithoutConsentSkipsToolDefinition() {
    RequestDTO request =
        new RequestDTO("run code", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", "key"))
        .thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
    when(toolRegistry.getAll()).thenReturn(List.of(pythonInterpreterTool));
    when(pythonInterpreterTool.getName()).thenReturn("python-interpreter");
    when(pythonInterpreterTool.requiresPerCallConsent()).thenReturn(true);
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of(enabledTool("python-interpreter")));

    chatService.sendMessage(chatId, userId, request);

    verify(requestSpec, never()).tools(any());
  }

  @Test
  void sendMessage_highRiskToolWithConsentWiresToolDefinition() {
    RequestDTO request =
        new RequestDTO(
            "run code",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            null,
            null,
            null,
            List.of(new RequestDTO.ToolConsent("python-interpreter", "HIGH", Map.of())));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", "key"))
        .thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
    when(toolRegistry.getAll()).thenReturn(List.of(pythonInterpreterTool));
    when(pythonInterpreterTool.getName()).thenReturn("python-interpreter");
    when(pythonInterpreterTool.requiresPerCallConsent()).thenReturn(true);
    when(pythonInterpreterTool.riskLevel()).thenReturn(ToolRiskLevel.HIGH);
    when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of(enabledTool("python-interpreter")));

    chatService.sendMessage(chatId, userId, request);

    verify(requestSpec).tools(any());
  }

  @Test
  void sendMessage_withMedia_addsMultimodalUserMessage() {
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("png".getBytes()))), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity latestUserMessage = new MessageEntity();
    latestUserMessage.setId(100L);
    latestUserMessage.setRole(Role.USER);
    latestUserMessage.setMessage("describe image");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(latestUserMessage));

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.CLAUDE, "claude-sonnet-4-5", "claude-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("claude-sonnet-4-5").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    Message lastMessage = messagesCaptor.getValue().getLast();
    assertThat(lastMessage).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) lastMessage).getMedia()).hasSize(1);
  }

  @Test
  void sendMessage_withMedia_storesMimeTypesInContext() {
    RequestDTO request =
        new RequestDTO(
            "describe files",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            "key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("a".getBytes())),
                new RequestDTO.MediaAttachment(
                    "application/pdf", Base64.getEncoder().encodeToString("b".getBytes()))), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of());

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());

    MessageEntity userMsg = savedMessages.getAllValues().get(0);
    assertThat(userMsg.getRole()).isEqualTo(Role.USER);
    assertThat(userMsg.getContext()).isEqualTo("attachments:image/png,application/pdf");
  }

  @Test
  void sendMessage_withConcurrentLatestUserMessage_attachesMediaToPersistedMessageId() {
    RequestDTO request =
        new RequestDTO(
            "my image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("abc".getBytes()))), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity concurrentNewerUserMessage = new MessageEntity();
    concurrentNewerUserMessage.setId(200L);
    concurrentNewerUserMessage.setRole(Role.USER);
    concurrentNewerUserMessage.setMessage("other request text");

    MessageEntity persistedCurrentUserMessage = new MessageEntity();
    persistedCurrentUserMessage.setId(100L);
    persistedCurrentUserMessage.setRole(Role.USER);
    persistedCurrentUserMessage.setMessage("my image");

    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(concurrentNewerUserMessage, persistedCurrentUserMessage));

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.CLAUDE, "claude-sonnet-4-5", "claude-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("claude-sonnet-4-5").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    List<Message> prompt = messagesCaptor.getValue();
    UserMessage firstUser = (UserMessage) prompt.get(1);
    UserMessage secondUser = (UserMessage) prompt.get(2);

    assertThat(firstUser.getText()).isEqualTo("my image");
    assertThat(firstUser.getMedia()).hasSize(1);
    assertThat(secondUser.getText()).isEqualTo("other request text");
    assertThat(secondUser.getMedia()).isEmpty();
  }

  @Test
  void sendMessage_withNullMediaEntry_throwsBadRequest_andSkipsPersistence() {
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            java.util.Arrays.asList((RequestDTO.MediaAttachment) null), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("media[0] must not be null");

    verify(messageRepository, never()).save(any());
    verify(providerClientFactory, never()).getClient(any(), anyString(), any());
  }

  @Test
  void sendMessage_withOversizedMedia_throwsBadRequest_andSkipsPersistence() {
    String oversized = Base64.getEncoder().encodeToString(new byte[6 * 1024 * 1024]);
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(new RequestDTO.MediaAttachment("image/png", oversized)), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds");

    verify(messageRepository, never()).save(any());
    verify(providerClientFactory, never()).getClient(any(), anyString(), any());
  }

  @Test
  void sendMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request = new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key", null, null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void listToolCallLogs_returnsLogsForOwnedMessage() {
    MessageEntity message = new MessageEntity();
    message.setId(55L);
    message.setChatId(chatId);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);

    ToolCallLogEntity log = new ToolCallLogEntity();
    log.setId(1L);
    log.setMessageId(55L);
    log.setToolName("BraveSearchTool.searchWeb");
    log.setInputPayload("{\"query\":\"latest news\"}");
    log.setOutputPayload("Web search results");
    log.setDurationMs(42L);

    when(messageRepository.findById(55L)).thenReturn(Optional.of(message));
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(toolCallLogRepository.findByMessageIdOrderByStartedAtAsc(55L)).thenReturn(List.of(log));

    var result = chatService.listToolCallLogs(55L, userId);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().toolName()).isEqualTo("BraveSearchTool.searchWeb");
    assertThat(result.getFirst().inputPayload()).contains("latest news");
  }

  @Test
  void sendMessage_respectsCustomMaxMediaAttachments() {
    ChatProperties customProperties = new ChatProperties();
    customProperties.setMaxMediaAttachments(1);
    ChatService customService = buildChatService(customProperties);

    RequestDTO request =
        new RequestDTO(
            "two images",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            "key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("a".getBytes())),
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("b".getBytes()))), null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    assertThatThrownBy(() -> customService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("media supports up to 1 attachments");
  }

  @Test
  void sendMessage_extractsTokenUsage_andPersistsOnAssistantMessage() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(50, 20)).build();
    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage("reply"))), metadata);
    when(callResponseSpec.chatResponse()).thenReturn(response);

    SendMessageResponseDTO result = chatService.sendMessage(chatId, userId, request);

    assertThat(result.message()).isEqualTo("reply");
    assertThat(result.promptTokens()).isEqualTo(50L);
    assertThat(result.generationTokens()).isEqualTo(20L);
    assertThat(result.totalTokens()).isEqualTo(70L);

    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());

    MessageEntity assistantMsg = savedMessages.getAllValues().get(1);
    assertThat(assistantMsg.getRole()).isEqualTo(Role.ASSISTANT);
    assertThat(assistantMsg.getPromptTokens()).isEqualTo(50L);
    assertThat(assistantMsg.getGenerationTokens()).isEqualTo(20L);
    assertThat(assistantMsg.getTotalTokens()).isEqualTo(70L);
    verify(chatMetrics)
        .recordRequest(RequestDTO.Provider.OPENAI, "gpt-5.2", Plan.FREE, "success");
    verify(chatMetrics).recordTokens(RequestDTO.Provider.OPENAI, "gpt-5.2", "prompt", 50L);
    verify(chatMetrics).recordTokens(RequestDTO.Provider.OPENAI, "gpt-5.2", "completion", 20L);
  }

  @Test
  void sendMessage_withDefaultMetadata_returnsZeroTokens() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key", null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "goog-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.GOOGLE, "gemini", "goog-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gemini").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    when(callResponseSpec.chatResponse()).thenReturn(response);

    SendMessageResponseDTO result = chatService.sendMessage(chatId, userId, request);

    assertThat(result.message()).isEqualTo("ok");
    assertThat(result.promptTokens()).isEqualTo(0L);
    assertThat(result.generationTokens()).isEqualTo(0L);
    assertThat(result.totalTokens()).isEqualTo(0L);
  }

  @Test
  void streamMessage_emitsTextChunks_andPersistsAssistantMessage() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(chatRepository.existsById(chatId)).thenReturn(true);
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
    ChatResponse chunk1 = new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))));
    ChatResponse chunk2 =
        new ChatResponse(List.of(new Generation(new AssistantMessage(" world"))), metadata);
    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.just(chunk1, chunk2));

    var emitter = chatService.streamMessage(chatId, userId, request);

    assertThat(emitter).isNotNull();

    // Verify user + assistant messages were persisted
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());

    MessageEntity userMsg = savedMessages.getAllValues().get(0);
    assertThat(userMsg.getRole()).isEqualTo(Role.USER);

    MessageEntity assistantMsg = savedMessages.getAllValues().get(1);
    assertThat(assistantMsg.getRole()).isEqualTo(Role.ASSISTANT);
    assertThat(assistantMsg.getMessage()).isEqualTo("Hello world");
    assertThat(assistantMsg.getPromptTokens()).isEqualTo(10L);
    assertThat(assistantMsg.getGenerationTokens()).isEqualTo(5L);
    assertThat(assistantMsg.getTotalTokens()).isEqualTo(15L);
  }

  @Test
  void streamMessage_providerError_sendsErrorEvent_andCompletesCleanly() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.error(
            new org.springframework.web.reactive.function.client.WebClientResponseException(
                400, "Bad Request", null, null, null)));

    var emitter = chatService.streamMessage(chatId, userId, request);

    assertThat(emitter).isNotNull();

    // Only user message should be persisted (no assistant message since stream errored)
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(1)).save(savedMessages.capture());
    assertThat(savedMessages.getValue().getRole()).isEqualTo(Role.USER);
  }

  @Test
  void streamMessage_managedMode_providerError_recordsUsageButDoesNotIncrementCounter() {
    // BILL-14 refund path: when a managed-mode stream fails with a provider error
    // (e.g. WebClientResponseException 500), the daily counter must NOT be incremented
    // (so the user does not lose a cap slot for a failed call), but the usage event
    // MUST still be recorded for analytics/auditing.
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.MANAGED);
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENROUTER)).thenReturn(Optional.empty());

    when(providerClientFactory.getClient(
            RequestDTO.Provider.OPENROUTER, "srv-openrouter", BillingMode.MANAGED))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", "srv-openrouter"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("openai/gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.error(
            new org.springframework.web.reactive.function.client.WebClientResponseException(
                500, "Internal Server Error", null, null, null)));

    chatService.streamMessage(chatId, userId, request);

    // Usage event is recorded for analytics even on provider errors.
    verify(usageEventService)
        .record(
            eq(userId),
            eq(RequestDTO.Provider.OPENROUTER),
            eq("openai/gpt-5.2"),
            any(),
            any(),
            any(),
            any(),
            eq(BillingMode.MANAGED));
    // Counter is NOT incremented — failed call does not consume a daily cap slot.
    verify(entitlementService, never()).incrementCounter(anyString());
  }

  @Test
  void streamMessage_managedMode_successfulStream_recordsUsageAndIncrementsCounter() {
    // BILL-14 refund path: on a successful managed-mode stream, both record() and
    // incrementCounter() must be invoked (user consumes exactly one daily cap slot).
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(chatRepository.existsById(chatId)).thenReturn(true);
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.MANAGED);
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENROUTER)).thenReturn(Optional.empty());

    when(providerClientFactory.getClient(
            RequestDTO.Provider.OPENROUTER, "srv-openrouter", BillingMode.MANAGED))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENROUTER, "openai/gpt-5.2", "srv-openrouter"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("openai/gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
    ChatResponse chunk =
        new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.just(chunk));

    chatService.streamMessage(chatId, userId, request);

    verify(usageEventService)
        .record(
            eq(userId),
            eq(RequestDTO.Provider.OPENROUTER),
            eq("openai/gpt-5.2"),
            eq(10L),
            eq(5L),
            eq(15L),
            any(),
            eq(BillingMode.MANAGED));
    verify(entitlementService).incrementCounter(userId);
  }

  @Test
  void streamMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request = new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key", null, null);

    assertThatThrownBy(() -> chatService.streamMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void extractProviderErrorMessage_parsesOpenAiJsonError() {
    String openAiBody =
        "{\"error\":{\"message\":\"The model `gpt-invalid` does not exist\","
            + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"model_not_found\"}}";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            400, "Bad Request", null, openAiBody.getBytes(), null);

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).isEqualTo("The model `gpt-invalid` does not exist");
  }

  @Test
  void extractProviderErrorMessage_returnsRawBody_whenNotJson() {
    String rawBody = "Something went wrong";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            500, "Internal Server Error", null, rawBody.getBytes(), null);

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).isEqualTo("Something went wrong");
  }

  @Test
  void extractProviderErrorMessage_fallsBack_whenBodyEmpty() {
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            400, "Bad Request", null, null, null);

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).contains("HTTP 400");
  }

  @Test
  void extractProviderErrorMessage_returnsRateLimitFallback_when429BodyEmpty() {
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            429, "Too Many Requests", null, null, null);

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).contains("rate limit exceeded");
    assertThat(result).contains("HTTP 429");
  }

  @Test
  void extractProviderErrorMessage_parsesTopLevelMessage() {
    String body = "{\"message\":\"Rate limit exceeded\"}";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            429, "Too Many Requests", null, body.getBytes(), null);

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).isEqualTo("Rate limit exceeded");
  }

  @Test
  void extractProviderErrorMessage_parsesOpenAiSdkServiceException() {
    var ex =
        com.openai.errors.UnexpectedStatusCodeException.builder()
            .statusCode(400)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("model_not_found")
                    .message("Invalid model")
                    .param(java.util.Optional.empty())
                    .type("invalid_request_error")
                    .build())
            .build();

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).isEqualTo("Invalid model");
  }

  @Test
  void extractProviderErrorMessage_returnsGenericMessage_forNonProviderError() {
    var ex = new RuntimeException("Something unexpected");

    String result = StreamErrorHandler.extractProviderMessage(ex);

    assertThat(result).isEqualTo("An error occurred while streaming the response.");
  }

  @Test
  void classifyStreamError_detectsTimeoutWithoutHttpStatus() {
    String result =
        StreamErrorHandler.classify(
            new RuntimeException("response timeout while streaming"), null);

    assertThat(result).isEqualTo("TIMEOUT");
  }

  @Test
  void classifyStreamError_detectsRateLimitFromHttpStatus() {
    String result = StreamErrorHandler.classify(new RuntimeException("any"), 429);

    assertThat(result).isEqualTo("RATE_LIMIT");
  }

  @Test
  void buildClientStreamErrorMessage_includesProviderModelStatusAndPartialHint() {
    String message =
        StreamErrorHandler.buildClientMessage(
            new StreamErrorHandler.StreamErrorDetails(
                RequestDTO.Provider.GOOGLE,
                "gemma-4-31b-it",
                503,
                "SERVER_ERROR",
                128L));

    assertThat(message)
        .isEqualTo(
            "Response from Google (gemma-4-31b-it) was interrupted (HTTP 503). The provider returned a server error. Partial response shown.");
  }

  @Test
  void expectedStreamFailures_includeProviderAndTimeoutKinds() {
    assertThat(StreamErrorHandler.isExpectedFailure("TIMEOUT")).isTrue();
    assertThat(StreamErrorHandler.isExpectedFailure("PROVIDER_ERROR")).isTrue();
    assertThat(StreamErrorHandler.isExpectedFailure("UNKNOWN")).isFalse();
  }

  // ── Auto-title tests ────────────────────────────────────────────────────────

  private void setupSendMessageMocks(ChatEntity chat, String apiKey) {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(apiKey));
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, apiKey, BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", apiKey))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
  }

  @Test
  void sendMessage_autoTitlesChat_whenChatHasNoTitle() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    // title is null

    setupSendMessageMocks(chat, "key");
    when(chatRepository.setTitleIfBlank(eq(chatId), anyString())).thenReturn(1);

    RequestDTO request =
        new RequestDTO("Hello world", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    SendMessageResponseDTO result = chatService.sendMessage(chatId, userId, request);

    assertThat(result.chatTitle()).isEqualTo("Hello world");
    assertThat(chat.getTitle()).isEqualTo("Hello world");
    verify(chatRepository).setTitleIfBlank(chatId, "Hello world");
  }

  @Test
  void sendMessage_doesNotOverwriteExistingTitle() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    chat.setTitle("My custom title");

    setupSendMessageMocks(chat, "key");

    RequestDTO request =
        new RequestDTO("Hello world", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    SendMessageResponseDTO result = chatService.sendMessage(chatId, userId, request);

    assertThat(result.chatTitle()).isNull();
    assertThat(chat.getTitle()).isEqualTo("My custom title");
  }

  @Test
  void sendMessage_truncatesLongMessageForTitle() {
    ChatProperties longTitleProps = new ChatProperties();
    longTitleProps.setMaxChatTitleLength(10);
    ChatService serviceWithShortTitleLimit = buildChatService(longTitleProps);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);

    setupSendMessageMocks(chat, "key");
    when(chatRepository.setTitleIfBlank(eq(chatId), anyString())).thenReturn(1);

    RequestDTO request =
        new RequestDTO("A very long message that exceeds the limit", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    SendMessageResponseDTO result = serviceWithShortTitleLimit.sendMessage(chatId, userId, request);

    assertThat(result.chatTitle()).isEqualTo("A very lon");
    assertThat(chat.getTitle()).isEqualTo("A very lon");
  }

  // ── FK constraint violation tests ─────────────────────────────────────────

  private static DataIntegrityViolationException chatFkViolation() {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new RuntimeException(
            "ERROR: insert or update on table \"messages\" violates foreign key constraint \"messages_chat_id_fkey\""));
  }

  private static void invokeNoArgTool(Object tool, String methodName) throws Exception {
    assertThat(tool).isNotNull();
    Method method = tool.getClass().getMethod(methodName);
    method.invoke(tool);
  }

  @Test
  void sendMessage_fkViolationOnUserMessage_throwsChatNotFound() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    // Simulate chat being deleted between lookup and message save
    when(messageRepository.save(any(MessageEntity.class)))
        .thenThrow(chatFkViolation());

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", "key", null, null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void sendMessage_nonChatFkViolation_rethrowsAsIs() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    // Simulate an unrelated integrity violation (not the chat FK)
    when(messageRepository.save(any(MessageEntity.class)))
        .thenThrow(new DataIntegrityViolationException("some other constraint"));

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", "key", null, null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("some other constraint");
  }

  @Test
  void sendMessage_fkViolationOnAssistantMessage_throwsChatNotFound() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("pong")))));

    AtomicLong ids = new AtomicLong(100);
    // First save (user message) succeeds, second save (assistant message) throws FK violation
    when(messageRepository.save(any(MessageEntity.class)))
        .thenAnswer(invocation -> {
          MessageEntity entity = invocation.getArgument(0);
          if (entity.getId() == null) {
            entity.setId(ids.getAndIncrement());
          }
          return entity;
        })
        .thenThrow(chatFkViolation());

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void streamMessage_chatDeletedDuringStream_skipsAssistantSave() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    ChatResponse chunk = new ChatResponse(List.of(new Generation(new AssistantMessage("reply"))));
    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.just(chunk));

    // Chat deleted during streaming — existsById returns false
    when(chatRepository.existsById(chatId)).thenReturn(false);

    var emitter = chatService.streamMessage(chatId, userId, request);
    assertThat(emitter).isNotNull();

    // Only user message should be saved (assistant message skipped because chat was deleted)
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(1)).save(savedMessages.capture());
    assertThat(savedMessages.getValue().getRole()).isEqualTo(Role.USER);
  }

  // ── editAndStreamMessage tests ───────────────────────────────────────────────

  @Test
  void editAndStreamMessage_updatesMessage_deletesSubsequent_andStreamsNewResponse() {
    Long messageId = 42L;
    RequestDTO request =
        new RequestDTO("updated content", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(chatRepository.existsById(chatId)).thenReturn(true);

    MessageEntity userMessage = new MessageEntity();
    userMessage.setId(messageId);
    userMessage.setChatId(chatId);
    userMessage.setRole(Role.USER);
    userMessage.setMessage("original content");
    when(messageRepository.findByIdAndChatId(messageId, chatId)).thenReturn(Optional.of(userMessage));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    ChatResponse chunk = new ChatResponse(List.of(new Generation(new AssistantMessage("new reply"))));
    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.just(chunk));

    var emitter = chatService.editAndStreamMessage(chatId, userId, messageId, request);

    assertThat(emitter).isNotNull();

    // User message content must be updated
    assertThat(userMessage.getMessage()).isEqualTo("updated content");

    // deleteByIdGreaterThanAndChatId must be called to remove subsequent messages
    verify(messageRepository).deleteByIdGreaterThanAndChatId(chatId, messageId);

    // user message save (update) + assistant message save = 2 saves
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    MessageEntity assistantMsg = savedMessages.getAllValues().get(1);
    assertThat(assistantMsg.getRole()).isEqualTo(Role.ASSISTANT);
    assertThat(assistantMsg.getMessage()).isEqualTo("new reply");
  }

  @Test
  void editAndStreamMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", "key", null, null);

    assertThatThrownBy(() -> chatService.editAndStreamMessage(chatId, userId, 1L, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void editAndStreamMessage_messageNotFound_throwsIllegalArgument() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByIdAndChatId(99L, chatId)).thenReturn(Optional.empty());

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", "key", null, null);

    assertThatThrownBy(() -> chatService.editAndStreamMessage(chatId, userId, 99L, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void editAndStreamMessage_nonUserMessage_throwsIllegalArgument() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setId(10L);
    assistantMessage.setChatId(chatId);
    assistantMessage.setRole(Role.ASSISTANT);
    assistantMessage.setMessage("assistant text");
    when(messageRepository.findByIdAndChatId(10L, chatId)).thenReturn(Optional.of(assistantMessage));

    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2", "key", null, null);

    assertThatThrownBy(() -> chatService.editAndStreamMessage(chatId, userId, 10L, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only user messages can be edited");
  }

  @Test
  void sendMessage_gemmaModel_usesUserMessageForInstructions() {
    RequestDTO request =
        new RequestDTO("hello", RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", "goog-key", null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "goog-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", "goog-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gemma-3-27b-it").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hi there")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    List<Message> prompt = messagesCaptor.getValue();
    // Gemma: no SystemMessage, but first message must be a UserMessage carrying the instructions
    assertThat(prompt).noneMatch(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage);
    assertThat(prompt.getFirst()).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) prompt.getFirst()).getText())
        .contains("System instructions for this conversation")
        .contains("helpful assistant");

    // Context string must NOT claim a system prompt was included
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    MessageEntity assistantMsg = savedMessages.getAllValues().get(1);
    assertThat(assistantMsg.getContext())
        .doesNotContain("including system prompt")
        .contains("instructions sent as user message");
  }

  @Test
  void sendMessage_geminiModel_includesSystemMessage() {
    RequestDTO request =
        new RequestDTO("hello", RequestDTO.Provider.GOOGLE, "gemini-2.5-flash", "goog-key", null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "goog-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.GOOGLE, "gemini-2.5-flash", "goog-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gemini-2.5-flash").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hi there")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    List<Message> prompt = messagesCaptor.getValue();
    assertThat(prompt).anyMatch(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage);
  }

  @Test
  void streamMessage_gemmaModel_usesUserMessageForInstructions_andContextIsAccurate() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", "goog-key", null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(chatRepository.existsById(chatId)).thenReturn(true);
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());

    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "goog-key", BillingMode.BYOK))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(RequestDTO.Provider.GOOGLE, "gemma-3-27b-it", "goog-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("gemma-3-27b-it").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamResponseSpec);

    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
    ChatResponse chunk1 = new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))));
    ChatResponse chunk2 =
        new ChatResponse(List.of(new Generation(new AssistantMessage(" Gemma"))), metadata);
    when(streamResponseSpec.chatResponse())
        .thenReturn(reactor.core.publisher.Flux.just(chunk1, chunk2));

    var emitter = chatService.streamMessage(chatId, userId, request);

    assertThat(emitter).isNotNull();

    // Verify no SystemMessage was sent to the provider
    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());
    List<Message> prompt = messagesCaptor.getValue();
    assertThat(prompt).noneMatch(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage);
    assertThat(prompt.getFirst()).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) prompt.getFirst()).getText())
        .contains("System instructions for this conversation")
        .contains("helpful assistant");

    // Verify persisted assistant message context says "instructions sent as user message"
    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    MessageEntity assistantMsg = savedMessages.getAllValues().get(1);
    assertThat(assistantMsg.getRole()).isEqualTo(Role.ASSISTANT);
    assertThat(assistantMsg.getMessage()).isEqualTo("Hello Gemma");
    assertThat(assistantMsg.getContext())
        .doesNotContain("including system prompt")
        .contains("instructions sent as user message");
  }

  private MessageEntity messageWithId(long id) {
    MessageEntity message = new MessageEntity();
    message.setId(id);
    message.setChatId(chatId);
    message.setMessage("message-" + id);
    message.setRole(Role.USER);
    return message;
  }

  private ChatToolEntity enabledTool(String toolName) {
    ChatToolEntity entity = new ChatToolEntity();
    entity.setChatId(chatId);
    entity.setToolName(toolName);
    entity.setEnabled(true);
    return entity;
  }

  private static Entitlements entitlements(Plan plan) {
    return new Entitlements(plan, 5, 5_242_880, 20_971_520, 20, Set.of("*"), 100, 1, null, null);
  }
}
