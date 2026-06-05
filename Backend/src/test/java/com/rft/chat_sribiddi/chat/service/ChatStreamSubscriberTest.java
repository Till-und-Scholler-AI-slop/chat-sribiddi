package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.QuotaReservation;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.ChatUsage;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class ChatStreamSubscriberTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ChatUsageRecorder chatUsageRecorder;
  @Mock private ToolCallLogRecorder toolCallLogRecorder;
  @Mock private ChatMetrics chatMetrics;
  @Mock private SseEmitter emitter;
  @Mock private QuotaReservation quotaReservation;
  @Mock private ChatMetrics.StreamSample streamSample;

  private ChatStreamSubscriber subscriber;
  private AutoCloseable clientCloseable;
  private ProviderClientHandle clientHandle;

  @BeforeEach
  void setUp() {
    subscriber =
        new ChatStreamSubscriber(
            chatRepository, messageRepository, chatUsageRecorder, toolCallLogRecorder, chatMetrics);
    clientCloseable = mock(AutoCloseable.class);
    clientHandle = new ProviderClientHandle(mock(ChatClient.class), clientCloseable);
  }

  // ---------------------------------------------------------------------------
  // handleSharedStreamError
  // ---------------------------------------------------------------------------

  @Test
  void handleSharedStreamError_sendsErrorEvent_rollsBackQuota_recordsUsage_completesEmitter()
      throws Exception {
    Throwable error = new RuntimeException("boom");
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    @SuppressWarnings("unchecked")
    Consumer<StreamErrorHandler.StreamErrorDetails> logError = mock(Consumer.class);
    @SuppressWarnings("unchecked")
    Consumer<TokenUsage> recordUsage = mock(Consumer.class);

    TokenUsage emptyUsage = new TokenUsage(null, null, null);
    when(chatUsageRecorder.tokenUsage(null)).thenReturn(emptyUsage);

    ReflectionTestUtils.invokeMethod(
        subscriber,
        "handleSharedStreamError",
        error,
        100L,
        lastUsage,
        streamSample,
        Provider.OPENAI,
        "gpt-5.2",
        emitter,
        quotaReservation,
        clientHandle,
        logError,
        recordUsage);

    verify(logError).accept(any(StreamErrorHandler.StreamErrorDetails.class));
    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    verify(quotaReservation).rollback();
    verify(recordUsage).accept(eq(emptyUsage));
    verify(chatMetrics).recordStreamEnd(streamSample);
    verify(emitter).complete();
    verify(clientCloseable).close();
  }

  // ---------------------------------------------------------------------------
  // handleSharedChunk
  // ---------------------------------------------------------------------------

  @Test
  void handleSharedChunk_sendsTextChunk_appendsToFullText_countsBytes() throws Exception {
    ChatResponse chunk =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))));
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    ReflectionTestUtils.invokeMethod(
        subscriber,
        "handleSharedChunk",
        chunk,
        fullText,
        streamedTextUtf8Bytes,
        lastUsage,
        emitter,
        null);

    assertThat(fullText.toString()).isEqualTo("Hello");
    assertThat(streamedTextUtf8Bytes.get()).isEqualTo(5L);
    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void handleSharedChunk_sendsReasoningChunk() throws Exception {
    AssistantMessage msg =
        AssistantMessage.builder()
            .content("text")
            .properties(Map.of("reasoningContent", "thinking..."))
            .toolCalls(List.of())
            .media(List.of())
            .build();
    ChatResponse chunk = new ChatResponse(List.of(new Generation(msg)));
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "handleSharedChunk",
          chunk,
          fullText,
          streamedTextUtf8Bytes,
          lastUsage,
          emitter,
          null);
    }

    assertThat(builders).hasSize(2);
    assertThat(builders.get(0).name).isEqualTo("text");
    assertThat(builders.get(0).dataObjects).hasSize(1);
    assertThat(builders.get(0).dataObjects.get(0)).isInstanceOf(StreamEventDTO.class);
    StreamEventDTO textDto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(textDto.type()).isEqualTo("text");
    assertThat(textDto.content()).isEqualTo("text");

    assertThat(builders.get(1).name).isEqualTo("reasoning");
    StreamEventDTO reasoningDto = (StreamEventDTO) builders.get(1).dataObjects.get(0);
    assertThat(reasoningDto.type()).isEqualTo("reasoning");
    assertThat(reasoningDto.content()).isEqualTo("thinking...");
  }

  @Test
  void handleSharedChunk_capturesUsage() throws Exception {
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build();
    ChatResponse chunk =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))), metadata);
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    ReflectionTestUtils.invokeMethod(
        subscriber,
        "handleSharedChunk",
        chunk,
        fullText,
        streamedTextUtf8Bytes,
        lastUsage,
        emitter,
        null);

    assertThat(lastUsage.get()).isNotNull();
    assertThat(lastUsage.get().getPromptTokens()).isEqualTo(10);
    assertThat(lastUsage.get().getCompletionTokens()).isEqualTo(5);
  }

  @Test
  void handleSharedChunk_withIOException_gracefullyHandlesEmitterFailure() throws Exception {
    ChatResponse chunk =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))));
    doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

    // Should not throw
    ReflectionTestUtils.invokeMethod(
        subscriber,
        "handleSharedChunk",
        chunk,
        new StringBuilder(),
        new AtomicLong(),
        new AtomicReference<>(),
        emitter,
        null);

    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  // ---------------------------------------------------------------------------
  // sendUnexpectedChunkErrorShared
  // ---------------------------------------------------------------------------

  @Test
  void sendUnexpectedChunkErrorShared_sendsErrorEventWithCorrectDto_rollsBackQuota_completesEmitter()
      throws Exception {
    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "sendUnexpectedChunkErrorShared",
          emitter,
          Provider.OPENAI,
          "gpt-5.2",
          quotaReservation,
          clientHandle);
    }

    verify(quotaReservation).rollback();
    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    verify(emitter).complete();
    verify(clientCloseable).close();

    assertThat(builders).hasSize(1);
    CapturingSseEventBuilder builder = builders.get(0);
    assertThat(builder.name).isEqualTo("error");
    assertThat(builder.dataObjects).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builder.dataObjects.get(0);
    assertThat(dto.type()).isEqualTo("error");
    assertThat(dto.content())
        .isEqualTo("An unexpected error occurred while processing the response stream.");
    assertThat(dto.provider()).isEqualTo("OPENAI");
    assertThat(dto.modelId()).isEqualTo("gpt-5.2");
    assertThat(dto.errorType()).isEqualTo("STREAM_CHUNK_ERROR");
    assertThat(dto.incomplete()).isTrue();
  }

  @Test
  void sendUnexpectedChunkErrorShared_withNullProviderAndModel_sendsErrorEventWithNullFields()
      throws Exception {
    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "sendUnexpectedChunkErrorShared",
          emitter,
          null,
          null,
          quotaReservation,
          clientHandle);
    }

    assertThat(builders).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(dto.provider()).isNull();
    assertThat(dto.modelId()).isNull();
    assertThat(dto.errorType()).isEqualTo("STREAM_CHUNK_ERROR");
  }

  // ---------------------------------------------------------------------------
  // saveStreamedAssistantMessage
  // ---------------------------------------------------------------------------

  @Test
  void saveStreamedAssistantMessage_happyPath_savesMessage_returnsTrue() {
    MessageEntity message = new MessageEntity();
    UUID chatId = UUID.randomUUID();
    when(messageRepository.save(message)).thenReturn(message);

    Boolean result =
        ReflectionTestUtils.invokeMethod(
            subscriber, "saveStreamedAssistantMessage", message, chatId, emitter);

    assertThat(result).isTrue();
    verify(messageRepository).save(message);
    verifyNoInteractions(emitter);
  }

  @Test
  void saveStreamedAssistantMessage_chatFkViolation_sendsChatDeletedError_returnsFalse()
      throws Exception {
    MessageEntity message = new MessageEntity();
    UUID chatId = UUID.randomUUID();
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException(
                "ERROR: insert or update on table \"messages\" violates foreign key constraint \"messages_chat_id_fkey\""));
    when(messageRepository.save(message)).thenThrow(ex);

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      Boolean result =
          ReflectionTestUtils.invokeMethod(
              subscriber, "saveStreamedAssistantMessage", message, chatId, emitter);

      assertThat(result).isFalse();
    }

    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    verify(emitter).complete();

    assertThat(builders).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(dto.type()).isEqualTo("error");
    assertThat(dto.errorType()).isEqualTo("CHAT_DELETED");
  }

  @Test
  void saveStreamedAssistantMessage_nonChatFkViolation_rethrows() {
    MessageEntity message = new MessageEntity();
    UUID chatId = UUID.randomUUID();
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException("some other constraint");
    when(messageRepository.save(message)).thenThrow(ex);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    subscriber, "saveStreamedAssistantMessage", message, chatId, emitter))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("some other constraint");
  }

  // ---------------------------------------------------------------------------
  // handleStreamCompletion
  // ---------------------------------------------------------------------------

  @Test
  void handleStreamCompletion_blankResponse_sendsErrorEvent_rollsBackQuota_completesEmitter()
      throws Exception {
    ChatStreamSubscriber.ChatStreamSubscription subscription = createSubscription();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber, "handleStreamCompletion", subscription, "", lastUsage, streamSample);
    }

    verify(subscription.quotaReservation()).rollback();
    verify(subscription.quotaReservation(), never()).commit();
    verify(subscription.emitter()).send(any(SseEmitter.SseEventBuilder.class));
    verify(subscription.emitter()).complete();
    verify(chatMetrics).recordStreamEnd(streamSample);
    verify(chatUsageRecorder).recordUsage(any(ChatUsage.class));

    assertThat(builders).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(dto.type()).isEqualTo("error");
    assertThat(dto.errorType()).isEqualTo("EMPTY_RESPONSE");
    assertThat(dto.receivedBytes()).isEqualTo(0L);
  }

  @Test
  void handleStreamCompletion_normalResponse_persistsMessage_sendsDoneEvent_commitsQuota()
      throws Exception {
    ChatStreamSubscriber.ChatStreamSubscription subscription = createSubscription();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    UUID chatId = subscription.chatId();

    when(chatRepository.existsById(chatId)).thenReturn(true);
    when(chatUsageRecorder.tokenUsage(any())).thenReturn(new TokenUsage(10L, 5L, 15L));
    when(messageRepository.save(any(MessageEntity.class)))
        .thenAnswer(
            invocation -> {
              MessageEntity msg = invocation.getArgument(0);
              msg.setId(123L);
              return msg;
            });

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "handleStreamCompletion",
          subscription,
          "Hello world",
          lastUsage,
          streamSample);
    }

    verify(chatRepository).existsById(chatId);
    verify(messageRepository).save(any(MessageEntity.class));
    verify(toolCallLogRecorder)
        .recordAssistantToolCalls(
            any(MessageEntity.class), any(ToolExecutionCollector.class), eq("Hello world"));
    verify(subscription.quotaReservation()).commit();
    verify(subscription.emitter()).complete();
    verify(chatMetrics).recordStreamEnd(streamSample);

    assertThat(builders).hasSize(1);
    StreamEventDTO doneDto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(doneDto.type()).isEqualTo("done");
    assertThat(doneDto.promptTokens()).isEqualTo(10L);
    assertThat(doneDto.generationTokens()).isEqualTo(5L);
    assertThat(doneDto.totalTokens()).isEqualTo(15L);
    assertThat(doneDto.messageId()).isEqualTo(123L);
    assertThat(doneDto.hasToolCalls()).isFalse();

    ArgumentCaptor<ChatUsage> usageCaptor = ArgumentCaptor.forClass(ChatUsage.class);
    verify(chatUsageRecorder).recordUsage(usageCaptor.capture());
    ChatUsage recordedUsage = usageCaptor.getValue();
    assertThat(recordedUsage.userId()).isEqualTo("user-123");
    assertThat(recordedUsage.provider()).isEqualTo(Provider.OPENAI);
    assertThat(recordedUsage.modelId()).isEqualTo("gpt-5.2");
    assertThat(recordedUsage.tokenUsage().promptTokens()).isEqualTo(10L);
    assertThat(recordedUsage.tokenUsage().generationTokens()).isEqualTo(5L);
    assertThat(recordedUsage.tokenUsage().totalTokens()).isEqualTo(15L);
    assertThat(recordedUsage.messageId()).isEqualTo(123L);
    assertThat(recordedUsage.credentialSource()).isEqualTo(CredentialSource.USER_KEY);
    assertThat(recordedUsage.providerCallSucceeded()).isTrue();
  }

  @Test
  void handleStreamCompletion_chatDeletedDuringStream_skipsSave_sendsChatDeletedError()
      throws Exception {
    ChatStreamSubscriber.ChatStreamSubscription subscription = createSubscription();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    UUID chatId = subscription.chatId();

    when(chatRepository.existsById(chatId)).thenReturn(false);
    when(chatUsageRecorder.tokenUsage(any())).thenReturn(new TokenUsage(null, null, null));

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "handleStreamCompletion",
          subscription,
          "Hello world",
          lastUsage,
          streamSample);
    }

    verify(chatRepository).existsById(chatId);
    verifyNoInteractions(messageRepository);
    verify(subscription.quotaReservation()).rollback();
    verify(subscription.quotaReservation(), never()).commit();

    assertThat(builders).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(dto.type()).isEqualTo("error");
    assertThat(dto.errorType()).isEqualTo("CHAT_DELETED");
  }

  @Test
  void handleStreamCompletion_saveFails_rollsBackQuota_doesNotSendDoneEvent() throws Exception {
    ChatStreamSubscriber.ChatStreamSubscription subscription = createSubscription();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    UUID chatId = subscription.chatId();

    when(chatRepository.existsById(chatId)).thenReturn(true);
    when(chatUsageRecorder.tokenUsage(any())).thenReturn(new TokenUsage(null, null, null));
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException(
                "ERROR: insert or update on table \"messages\" violates foreign key constraint \"messages_chat_id_fkey\""));
    when(messageRepository.save(any(MessageEntity.class))).thenThrow(ex);

    List<CapturingSseEventBuilder> builders = new ArrayList<>();
    try (MockedStatic<SseEmitter> mocked = mockStatic(SseEmitter.class)) {
      mocked.when(SseEmitter::event).thenAnswer(inv -> new CapturingSseEventBuilder(builders));

      ReflectionTestUtils.invokeMethod(
          subscriber,
          "handleStreamCompletion",
          subscription,
          "Hello world",
          lastUsage,
          streamSample);
    }

    verify(subscription.quotaReservation()).rollback();
    verify(subscription.quotaReservation(), never()).commit();
    verify(subscription.emitter()).send(any(SseEmitter.SseEventBuilder.class));

    assertThat(builders).hasSize(1);
    StreamEventDTO dto = (StreamEventDTO) builders.get(0).dataObjects.get(0);
    assertThat(dto.type()).isEqualTo("error");
    assertThat(dto.errorType()).isEqualTo("CHAT_DELETED");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ChatStreamSubscriber.ChatStreamSubscription createSubscription() {
    return new ChatStreamSubscriber.ChatStreamSubscription(
        mock(ChatClient.ChatClientRequestSpec.class),
        emitter,
        clientHandle,
        "user-123",
        Provider.OPENAI,
        "gpt-5.2",
        CredentialSource.USER_KEY,
        UUID.randomUUID(),
        "assistant-context",
        "updated-title",
        new ToolExecutionCollector(),
        quotaReservation);
  }

  private static class CapturingSseEventBuilder implements SseEmitter.SseEventBuilder {
    private final List<CapturingSseEventBuilder> registry;
    String name;
    final List<Object> dataObjects = new ArrayList<>();
    final List<MediaType> mediaTypes = new ArrayList<>();

    CapturingSseEventBuilder(List<CapturingSseEventBuilder> registry) {
      this.registry = registry;
      registry.add(this);
    }

    @Override
    public SseEmitter.SseEventBuilder id(String id) {
      return this;
    }

    @Override
    public SseEmitter.SseEventBuilder name(String name) {
      this.name = name;
      return this;
    }

    @Override
    public SseEmitter.SseEventBuilder data(Object data) {
      dataObjects.add(data);
      return this;
    }

    @Override
    public SseEmitter.SseEventBuilder data(Object data, MediaType mediaType) {
      dataObjects.add(data);
      mediaTypes.add(mediaType);
      return this;
    }

    @Override
    public SseEmitter.SseEventBuilder comment(String comment) {
      return this;
    }

    @Override
    public SseEmitter.SseEventBuilder reconnectTime(long reconnectTime) {
      return this;
    }

    @Override
    public Set<org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType>
        build() {
      return Set.of();
    }
  }
}
