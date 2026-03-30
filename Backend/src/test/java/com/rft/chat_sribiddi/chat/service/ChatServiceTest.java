package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.usage.UsageService;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ApiKeyService apiKeyService;
  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private com.rft.chat_sribiddi.auth.UserSettingsService userSettingsService;
  @Mock private UsageService usageService;

  @Mock private ChatClient chatClient;
  @Mock private ChatClient.ChatClientRequestSpec requestSpec;
  @Mock private ChatClient.CallResponseSpec callResponseSpec;
  @Mock private ChatClient.StreamResponseSpec streamResponseSpec;

  private ChatService chatService;
  private UUID chatId;
  private String userId;

  @BeforeEach
  void setUp() {
    ChatProperties chatProperties = new ChatProperties();
    chatService =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory, chatProperties, userSettingsService, new com.rft.chat_sribiddi.websearch.WebSearchProperties(), new com.rft.chat_sribiddi.tools.CurrentDateTimeTool(), usageService);
    chatId = UUID.randomUUID();
    userId = "user-123";

    lenient().when(userSettingsService.getCustomInstructions(any())).thenReturn(java.util.Optional.empty());

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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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
    verify(providerClientFactory, never()).getClient(any(), anyString());
  }

  @Test
  void sendMessage_withOversizedMedia_throwsBadRequest_andSkipsProviderCall() {
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

    MessageEntity latestUserMessage = new MessageEntity();
    latestUserMessage.setId(100L);
    latestUserMessage.setRole(Role.USER);
    latestUserMessage.setMessage("describe image");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(latestUserMessage));

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds");

    verify(providerClientFactory, never()).getClient(any(), anyString());
  }

  @Test
  void sendMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request = new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key", null, null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void sendMessage_respectsCustomMaxMediaAttachments() {
    ChatProperties customProperties = new ChatProperties();
    customProperties.setMaxMediaAttachments(1);
    ChatService customService =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory, customProperties, userSettingsService, new com.rft.chat_sribiddi.websearch.WebSearchProperties(), new com.rft.chat_sribiddi.tools.CurrentDateTimeTool(), usageService);

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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    verify(usageService).recordUsage(
        eq(userId), eq(chatId), eq("gpt-5.2"), eq("OPENAI"),
        eq(50L), eq(20L), eq(70L));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.GOOGLE, "key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.GOOGLE, "gemini", "key"))
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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).isEqualTo("The model `gpt-invalid` does not exist");
  }

  @Test
  void extractProviderErrorMessage_returnsRawBody_whenNotJson() {
    String rawBody = "Something went wrong";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            500, "Internal Server Error", null, rawBody.getBytes(), null);

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).isEqualTo("Something went wrong");
  }

  @Test
  void extractProviderErrorMessage_fallsBack_whenBodyEmpty() {
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            400, "Bad Request", null, null, null);

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).contains("HTTP 400");
  }

  @Test
  void extractProviderErrorMessage_returnsRateLimitFallback_when429BodyEmpty() {
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            429, "Too Many Requests", null, null, null);

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).contains("rate limit exceeded");
    assertThat(result).contains("HTTP 429");
  }

  @Test
  void extractProviderErrorMessage_parsesTopLevelMessage() {
    String body = "{\"message\":\"Rate limit exceeded\"}";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            429, "Too Many Requests", null, body.getBytes(), null);

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).isEqualTo("Rate limit exceeded");
  }

  @Test
  void extractProviderErrorMessage_returnsGenericMessage_forNonProviderError() {
    var ex = new RuntimeException("Something unexpected");

    String result = ChatService.extractProviderErrorMessage(ex);

    assertThat(result).isEqualTo("An error occurred while streaming the response.");
  }

  // ── Auto-title tests ────────────────────────────────────────────────────────

  private void setupSendMessageMocks(ChatEntity chat, String apiKey) {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(apiKey));
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, apiKey))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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
    ChatService serviceWithShortTitleLimit =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory, longTitleProps, userSettingsService, new com.rft.chat_sribiddi.websearch.WebSearchProperties(), new com.rft.chat_sribiddi.tools.CurrentDateTimeTool(), usageService);

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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
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
}
