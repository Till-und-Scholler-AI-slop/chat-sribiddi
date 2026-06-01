package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.ManagedQuotaReservation;
import com.rft.chat_sribiddi.billing.QuotaReservation;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.ChatUsage;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamSubscriber {

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ChatUsageRecorder chatUsageRecorder;
  private final ToolCallLogRecorder toolCallLogRecorder;
  private final ChatMetrics chatMetrics;

  void subscribe(ChatStreamSubscription subscription) {
    subscription
        .toolExecutionCollector()
        .setEventSink(event -> sendToolCallEvent(subscription.emitter(), event));
    subscribeShared(
        subscription.promptSpec(),
        subscription.emitter(),
        subscription.clientHandle(),
        subscription.quotaReservation(),
        subscription.provider(),
        subscription.modelId(),
        new StreamCallbacks(
            () ->
                sendUnexpectedChunkErrorShared(
                    subscription.emitter(),
                    subscription.provider(),
                    subscription.modelId(),
                    subscription.quotaReservation(),
                    subscription.clientHandle()),
            (error, streamedTextUtf8Bytes, lastUsage, streamSample) ->
                handleSharedStreamError(
                    error,
                    streamedTextUtf8Bytes,
                    lastUsage,
                    streamSample,
                    subscription.provider(),
                    subscription.modelId(),
                    subscription.emitter(),
                    subscription.quotaReservation(),
                    subscription.clientHandle(),
                    details -> logStreamingError(subscription.chatId(), details, error),
                    tokenUsage ->
                        chatUsageRecorder.recordUsage(
                            new ChatUsage(
                                subscription.userId(),
                                subscription.provider(),
                                subscription.modelId(),
                                tokenUsage,
                                null,
                                subscription.billingMode(),
                                subscription.plan(),
                                false))),
            (fullText, lastUsage, streamSample) ->
                handleStreamCompletion(subscription, fullText, lastUsage, streamSample)));
  }

  private void sendToolCallEvent(SseEmitter emitter, ToolExecutionCollector.Event event) {
    String eventType =
        switch (event.status()) {
          case RUNNING -> "tool_call_started";
          case SUCCESS -> "tool_call_completed";
          case FAILED -> "tool_call_failed";
        };
    try {
      emitter.send(
          SseEmitter.event()
              .name(eventType)
              .data(
                  new StreamEventDTO(
                      eventType,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      event.clientId(),
                      event.toolName(),
                      event.status(),
                      event.inputPayload(),
                      event.outputPayload(),
                      event.startedAt(),
                      event.durationMs(),
                      event.errorMessage(),
                      event.displayType()),
                  MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      log.debug("Failed to send tool call SSE event type={} clientId={}", eventType, event.clientId());
    }
  }

  void subscribeStateless(StatelessStreamSubscription subscription) {
    subscribeShared(
        subscription.promptSpec(),
        subscription.emitter(),
        subscription.clientHandle(),
        subscription.quotaReservation(),
        subscription.provider(),
        subscription.modelId(),
        new StreamCallbacks(
            () ->
                sendUnexpectedChunkErrorShared(
                    subscription.emitter(),
                    null,
                    null,
                    subscription.quotaReservation(),
                    subscription.clientHandle()),
            (error, streamedTextUtf8Bytes, lastUsage, streamSample) ->
                handleSharedStreamError(
                    error,
                    streamedTextUtf8Bytes,
                    lastUsage,
                    streamSample,
                    subscription.provider(),
                    subscription.modelId(),
                    subscription.emitter(),
                    subscription.quotaReservation(),
                    subscription.clientHandle(),
                    details ->
                        log.warn(
                            "Stateless streaming error type={} provider={} model={} statusCode={} receivedBytes={} providerMessage={}",
                            details.errorType(),
                            details.provider().name(),
                            details.modelId(),
                            details.statusCode(),
                            details.receivedBytes(),
                            StreamErrorHandler.extractProviderMessage(error)),
                    tokenUsage ->
                        chatUsageRecorder.recordRequestMetric(
                            subscription.provider(), subscription.modelId(), Plan.FREE, false)),
            (fullText, lastUsage, streamSample) ->
                handleStatelessStreamCompletion(subscription, lastUsage, streamSample)));
  }

  private void subscribeShared(
      ChatClient.ChatClientRequestSpec promptSpec,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      QuotaReservation quotaReservation,
      Provider provider,
      String modelId,
      StreamCallbacks callbacks) {
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    ChatMetrics.StreamSample streamSample = chatMetrics.startStream(provider, modelId);

    try {
      promptSpec
          .stream()
          .chatResponse()
          .subscribe(
              chunk ->
                  handleSharedChunk(
                      chunk, fullText, streamedTextUtf8Bytes, lastUsage, emitter, callbacks),
              error ->
                  callbacks.onStreamError()
                      .accept(error, streamedTextUtf8Bytes.get(), lastUsage, streamSample),
              () ->
                  callbacks.onStreamCompletion()
                      .accept(fullText.toString(), lastUsage, streamSample));
    } catch (RuntimeException | Error error) {
      quotaReservation.rollback();
      chatMetrics.recordStreamEnd(streamSample);
      throw error;
    }

    emitter.onTimeout(
        () -> {
          quotaReservation.rollback();
          chatMetrics.recordStreamEnd(streamSample);
          clientHandle.close();
        });
    emitter.onError(
        _ -> {
          quotaReservation.rollback();
          chatMetrics.recordStreamEnd(streamSample);
          clientHandle.close();
        });
    emitter.onCompletion(() -> chatMetrics.recordStreamEnd(streamSample));
  }

  private void handleSharedChunk(
      ChatResponse chunk,
      StringBuilder fullText,
      AtomicLong streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      SseEmitter emitter,
      StreamCallbacks callbacks) {
    try {
      String text = ChatResponseExtractor.text(chunk);

      if (text != null && !text.isEmpty()) {
        fullText.append(text);
        streamedTextUtf8Bytes.addAndGet(ChatResponseExtractor.utf8Length(text));
        emitter.send(
            SseEmitter.event()
                .name("text")
                .data(
                    new StreamEventDTO(
                        "text", text, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                    MediaType.APPLICATION_JSON));
      }

      String reasoning = ChatResponseExtractor.reasoningContent(chunk);
      if (reasoning != null && !reasoning.isEmpty()) {
        emitter.send(
            SseEmitter.event()
                .name("reasoning")
                .data(
                    new StreamEventDTO(
                        "reasoning", reasoning, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                    MediaType.APPLICATION_JSON));
      }

      ChatResponseExtractor.usage(chunk).ifPresent(lastUsage::set);
    } catch (IOException | IllegalStateException e) {
      log.debug("SSE send failed (client gone): {}", e.toString());
    } catch (Exception e) {
      log.warn("Unexpected error processing stream chunk", e);
      callbacks.onUnexpectedChunkError().run();
    }
  }

  private void sendUnexpectedChunkErrorShared(
      SseEmitter emitter,
      Provider provider,
      String modelId,
      QuotaReservation quotaReservation,
      ProviderClientHandle clientHandle) {
    quotaReservation.rollback();
    try {
      emitter.send(
          SseEmitter.event()
              .name(StreamErrorHandler.EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      StreamErrorHandler.EVENT_ERROR,
                      "An unexpected error occurred while processing the response stream.",
                      null, null, null, null, null, null,
                      provider != null ? provider.name() : null,
                      modelId,
                      null,
                      "STREAM_CHUNK_ERROR",
                      null,
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException ex) {
      log.debug("Failed to send unexpected-chunk error event (client gone)");
    } finally {
      try {
        emitter.complete();
      } catch (Exception ignored) {
      }
      clientHandle.close();
    }
  }

  private void handleSharedStreamError(
      Throwable error,
      long streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample,
      Provider provider,
      String modelId,
      SseEmitter emitter,
      QuotaReservation quotaReservation,
      ProviderClientHandle clientHandle,
      Consumer<StreamErrorHandler.StreamErrorDetails> logError,
      Consumer<TokenUsage> recordUsage) {
    StreamErrorHandler.StreamErrorDetails details =
        StreamErrorHandler.describe(provider, modelId, streamedTextUtf8Bytes, error);
    TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());
    logError.accept(details);
    try (clientHandle) {
      emitter.send(
          SseEmitter.event()
              .name(StreamErrorHandler.EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      StreamErrorHandler.EVENT_ERROR,
                      StreamErrorHandler.buildClientMessage(details),
                      null, null, null, null, null, null,
                      details.provider().name(),
                      details.modelId(),
                      details.statusCode(),
                      details.errorType(),
                      details.receivedBytes(),
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      log.debug("Failed to send SSE error event (client disconnected)");
    } finally {
      quotaReservation.rollback();
      recordUsage.accept(tokenUsage);
      chatMetrics.recordStreamEnd(streamSample);
      try {
        emitter.complete();
      } catch (Exception ignored) {
      }
    }
  }

  private void handleStreamCompletion(
      ChatStreamSubscription subscription,
      String fullText,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample) {
    AtomicReference<Long> savedMessageId = new AtomicReference<>();
    boolean streamCompletedSuccessfully = false;
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      String assistantResponse = fullText;
      if (assistantResponse.isBlank()) {
        subscription.quotaReservation().rollback();
        try {
          subscription.emitter().send(
              SseEmitter.event()
                  .name(StreamErrorHandler.EVENT_ERROR)
                  .data(
                      new StreamEventDTO(
                          StreamErrorHandler.EVENT_ERROR,
                          "The provider returned an empty response. Please try again or switch models.",
                          null, null, null, null, null, null,
                          subscription.provider().name(),
                          subscription.modelId(),
                          null,
                          "EMPTY_RESPONSE",
                          0L,
                          true),
                      MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
        }
        try {
          subscription.emitter().complete();
        } catch (Exception ignored) {
        }
        return;
      }
      TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());

      MessageEntity assistantMessage = new MessageEntity();
      assistantMessage.setChatId(subscription.chatId());
      assistantMessage.setMessage(assistantResponse);
      assistantMessage.setRole(Role.ASSISTANT);
      assistantMessage.setContext(subscription.assistantContext());
      assistantMessage.setCreatedAt(Instant.now());
      assistantMessage.setPromptTokens(tokenUsage.promptTokens());
      assistantMessage.setGenerationTokens(tokenUsage.generationTokens());
      assistantMessage.setTotalTokens(tokenUsage.totalTokens());

      if (!chatRepository.existsById(subscription.chatId())) {
        log.warn(
            "Chat {} was deleted during streaming — skipping assistant message save",
            subscription.chatId());
        subscription.quotaReservation().rollback();
        sendChatDeletedError(subscription.emitter());
        return;
      }

      if (!saveStreamedAssistantMessage(
          assistantMessage, subscription.chatId(), subscription.emitter())) {
        subscription.quotaReservation().rollback();
        return;
      }
      savedMessageId.set(assistantMessage.getId());

      toolCallLogRecorder.recordAssistantToolCalls(
          assistantMessage, subscription.toolExecutionCollector(), assistantResponse);

      subscription.emitter().send(
          SseEmitter.event()
              .name("done")
              .data(
                  new StreamEventDTO(
                      "done",
                      null,
                      tokenUsage.promptTokens(),
                      tokenUsage.generationTokens(),
                      tokenUsage.totalTokens(),
                      subscription.updatedTitle(),
                      assistantMessage.getId(),
                      !subscription.toolExecutionCollector().isEmpty(),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null),
                  MediaType.APPLICATION_JSON));
      subscription.quotaReservation().commit();
      streamCompletedSuccessfully = true;
      subscription.emitter().complete();
    } catch (IOException e) {
      log.warn("Failed to send SSE done event", e);
      subscription.quotaReservation().commit();
      subscription.emitter().complete();
    } finally {
      TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());
      chatUsageRecorder.recordUsage(
          new ChatUsage(
              subscription.userId(),
              subscription.provider(),
              subscription.modelId(),
              tokenUsage,
              savedMessageId.get(),
              subscription.billingMode(),
              subscription.plan(),
              streamCompletedSuccessfully));
      chatMetrics.recordStreamEnd(streamSample);
    }
  }

  private boolean saveStreamedAssistantMessage(
      MessageEntity message, UUID chatId, SseEmitter emitter) {
    try {
      messageRepository.save(message);
      return true;
    } catch (DataIntegrityViolationException ex) {
      if (DbConstraintChecker.isChatFkViolation(ex)) {
        log.warn(
            "Failed to save assistant message for chat {} — chat no longer exists", chatId, ex);
        sendChatDeletedError(emitter);
        return false;
      }
      throw ex;
    }
  }

  private void logStreamingError(
      UUID chatId, StreamErrorHandler.StreamErrorDetails details, Throwable error) {
    String baseMessage =
        "Streaming error type={} provider={} model={} chatId={} statusCode={} receivedBytes={} providerMessage={}";

    if (StreamErrorHandler.isExpectedFailure(details.errorType())) {
      log.warn(
          baseMessage,
          details.errorType(),
          details.provider().name(),
          details.modelId(),
          chatId,
          details.statusCode(),
          details.receivedBytes(),
          StreamErrorHandler.extractProviderMessage(error));
      log.debug(
          "Streaming error stacktrace type={} provider={} model={} chatId={}",
          details.errorType(),
          details.provider().name(),
          details.modelId(),
          chatId,
          error);
      return;
    }

    log.error(
        baseMessage,
        details.errorType(),
        details.provider().name(),
        details.modelId(),
        chatId,
        details.statusCode(),
        details.receivedBytes(),
        StreamErrorHandler.extractProviderMessage(error),
        error);
  }

  private void sendChatDeletedError(SseEmitter emitter) {
    try {
      emitter.send(
          SseEmitter.event()
              .name(StreamErrorHandler.EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      StreamErrorHandler.EVENT_ERROR,
                      "Chat was deleted while response was being generated.",
                      null, null, null, null, null, null,
                      null, null, null, "CHAT_DELETED", null, true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn("Failed to send SSE chat-deleted error event", e);
    } finally {
      emitter.complete();
    }
  }

  private void handleStatelessStreamCompletion(
      StatelessStreamSubscription subscription,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample) {
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());
      subscription.quotaReservation().commit();
      subscription.emitter().send(
          SseEmitter.event()
              .name("done")
              .data(
                  new StreamEventDTO(
                      "done",
                      null,
                      tokenUsage.promptTokens(),
                      tokenUsage.generationTokens(),
                      tokenUsage.totalTokens(),
                      null,
                      null,
                      false,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null),
                  MediaType.APPLICATION_JSON));
      subscription.emitter().complete();
    } catch (IOException | IllegalStateException e) {
      log.debug("Failed to send stateless SSE done event (client gone)");
      try {
        subscription.emitter().complete();
      } catch (Exception ignored) {
      }
    } finally {
      chatUsageRecorder.recordRequestMetric(
          subscription.provider(), subscription.modelId(), Plan.FREE, true);
      chatMetrics.recordStreamEnd(streamSample);
    }
  }

  private record StreamCallbacks(
      Runnable onUnexpectedChunkError,
      StreamErrorCallback onStreamError,
      StreamCompletionCallback onStreamCompletion) {}

  @FunctionalInterface
  private interface StreamErrorCallback {
    void accept(
        Throwable error,
        long streamedTextUtf8Bytes,
        AtomicReference<Usage> lastUsage,
        ChatMetrics.StreamSample streamSample);
  }

  @FunctionalInterface
  private interface StreamCompletionCallback {
    void accept(
        String fullText, AtomicReference<Usage> lastUsage, ChatMetrics.StreamSample streamSample);
  }

  record ChatStreamSubscription(
      ChatClient.ChatClientRequestSpec promptSpec,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      String userId,
      Provider provider,
      String modelId,
      BillingMode billingMode,
      Plan plan,
      UUID chatId,
      String assistantContext,
      String updatedTitle,
      ToolExecutionCollector toolExecutionCollector,
      ManagedQuotaReservation quotaReservation) {}

  record StatelessStreamSubscription(
      ChatClient.ChatClientRequestSpec promptSpec,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      Provider provider,
      String modelId,
      QuotaReservation quotaReservation) {}
}
