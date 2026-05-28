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

  private static final String EVENT_ERROR = "error";
  private static final String CHAT_FK_CONSTRAINT = "messages_chat_id_fkey";

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ChatUsageRecorder chatUsageRecorder;
  private final ToolCallLogRecorder toolCallLogRecorder;
  private final ChatMetrics chatMetrics;

  void subscribe(ChatStreamSubscription subscription) {
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    ChatMetrics.StreamSample streamSample =
        chatMetrics.startStream(subscription.provider(), subscription.modelId());

    try {
      subscription
          .promptSpec()
          .stream()
          .chatResponse()
          .subscribe(
              chunk ->
                  handleStreamChunk(
                      chunk, fullText, streamedTextUtf8Bytes, lastUsage, subscription),
              error -> handleStreamError(subscription, error, streamedTextUtf8Bytes.get(),
                  lastUsage, streamSample),
              () -> handleStreamCompletion(subscription, fullText, lastUsage, streamSample));
    } catch (RuntimeException | Error error) {
      subscription.quotaReservation().rollback();
      chatMetrics.recordStreamEnd(streamSample);
      throw error;
    }

    subscription.emitter().onTimeout(
        () -> {
          subscription.quotaReservation().rollback();
          chatMetrics.recordStreamEnd(streamSample);
          subscription.clientHandle().close();
        });
    subscription.emitter().onError(
        _ -> {
          subscription.quotaReservation().rollback();
          chatMetrics.recordStreamEnd(streamSample);
          subscription.clientHandle().close();
        });
    subscription.emitter().onCompletion(() -> chatMetrics.recordStreamEnd(streamSample));
  }

  private void handleStreamChunk(
      ChatResponse chunk,
      StringBuilder fullText,
      AtomicLong streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      ChatStreamSubscription subscription) {
    SseEmitter emitter = subscription.emitter();
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
    } catch (IOException e) {
      log.warn("Failed to send SSE event", e);
    } catch (Exception e) {
      log.warn("Unexpected error processing stream chunk", e);
      sendUnexpectedChunkError(subscription);
    }
  }

  private void sendUnexpectedChunkError(ChatStreamSubscription subscription) {
    subscription.quotaReservation().rollback();
    try {
      subscription.emitter().send(
          SseEmitter.event()
              .name(EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      EVENT_ERROR,
                      "An unexpected error occurred while processing the response stream.",
                      null, null, null, null, null, null,
                      subscription.provider().name(),
                      subscription.modelId(),
                      null,
                      "STREAM_CHUNK_ERROR",
                      null,
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException io) {
      log.warn("Failed to send SSE unexpected-chunk error event", io);
    } finally {
      subscription.emitter().complete();
      subscription.clientHandle().close();
    }
  }

  private void handleStreamError(
      ChatStreamSubscription subscription,
      Throwable error,
      long streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample) {
    StreamErrorHandler.StreamErrorDetails streamErrorDetails =
        StreamErrorHandler.describe(
            subscription.provider(), subscription.modelId(), streamedTextUtf8Bytes, error);
    logStreamingError(subscription.chatId(), streamErrorDetails, error);
    TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      subscription.emitter().send(
          SseEmitter.event()
              .name(EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      EVENT_ERROR,
                      StreamErrorHandler.buildClientMessage(streamErrorDetails),
                      null, null, null, null, null, null,
                      streamErrorDetails.provider().name(),
                      streamErrorDetails.modelId(),
                      streamErrorDetails.statusCode(),
                      streamErrorDetails.errorType(),
                      streamErrorDetails.receivedBytes(),
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn("Failed to send SSE error event", e);
    } finally {
      subscription.quotaReservation().rollback();
      chatUsageRecorder.recordUsage(
          new ChatUsage(
              subscription.userId(),
              subscription.provider(),
              subscription.modelId(),
              tokenUsage,
              null,
              subscription.billingMode(),
              subscription.plan(),
              false));
      chatMetrics.recordStreamEnd(streamSample);
      subscription.emitter().complete();
    }
  }

  private void handleStreamCompletion(
      ChatStreamSubscription subscription,
      StringBuilder fullText,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample) {
    AtomicReference<Long> savedMessageId = new AtomicReference<>();
    boolean streamCompletedSuccessfully = false;
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      String assistantResponse = fullText.toString();
      if (assistantResponse.isBlank()) {
        subscription.quotaReservation().rollback();
        subscription.emitter().send(
            SseEmitter.event()
                .name(EVENT_ERROR)
                .data(
                    new StreamEventDTO(
                        EVENT_ERROR,
                        "The provider returned an empty response. Please try again or switch models.",
                        null, null, null, null, null, null,
                        subscription.provider().name(),
                        subscription.modelId(),
                        null,
                        "EMPTY_RESPONSE",
                        0L,
                        true),
                    MediaType.APPLICATION_JSON));
        subscription.emitter().complete();
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
      if (isChatFkViolation(ex)) {
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

  private static boolean isChatFkViolation(DataIntegrityViolationException ex) {
    Throwable cause = ex.getMostSpecificCause();
    String message = cause.getMessage();
    return message != null && message.contains(CHAT_FK_CONSTRAINT);
  }

  private void sendChatDeletedError(SseEmitter emitter) {
    try {
      emitter.send(
          SseEmitter.event()
              .name(EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      EVENT_ERROR,
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

  void subscribeStateless(StatelessStreamSubscription subscription) {
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    ChatMetrics.StreamSample streamSample =
        chatMetrics.startStream(subscription.provider(), subscription.modelId());

    try {
      subscription
          .promptSpec()
          .stream()
          .chatResponse()
          .subscribe(
              chunk ->
                  handleStatelessStreamChunk(
                      chunk, fullText, streamedTextUtf8Bytes, lastUsage, subscription),
              error ->
                  handleStatelessStreamError(
                      subscription, error, streamedTextUtf8Bytes.get(), lastUsage, streamSample),
              () -> handleStatelessStreamCompletion(subscription, lastUsage, streamSample));
    } catch (RuntimeException | Error error) {
      subscription.quotaReservation().rollback();
      chatMetrics.recordStreamEnd(streamSample);
      throw error;
    }

    subscription.emitter().onTimeout(
        () -> {
          subscription.quotaReservation().rollback();
          chatMetrics.recordStreamEnd(streamSample);
          subscription.clientHandle().close();
        });
    subscription.emitter().onError(
        _ -> {
          subscription.quotaReservation().rollback();
          chatMetrics.recordStreamEnd(streamSample);
          subscription.clientHandle().close();
        });
    subscription.emitter().onCompletion(() -> chatMetrics.recordStreamEnd(streamSample));
  }

  private void handleStatelessStreamChunk(
      ChatResponse chunk,
      StringBuilder fullText,
      AtomicLong streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      StatelessStreamSubscription subscription) {
    SseEmitter emitter = subscription.emitter();
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
    } catch (IOException e) {
      log.warn("Failed to send stateless SSE event", e);
    } catch (Exception e) {
      log.warn("Unexpected error processing stateless stream chunk", e);
      sendStatelessUnexpectedChunkError(subscription);
    }
  }

  private void sendStatelessUnexpectedChunkError(StatelessStreamSubscription subscription) {
    subscription.quotaReservation().rollback();
    try {
      subscription.emitter().send(
          SseEmitter.event()
              .name(EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      EVENT_ERROR,
                      "An unexpected error occurred while processing the response stream.",
                      null, null, null, null, null, null,
                      null, null, null, "STREAM_CHUNK_ERROR", null, true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException io) {
      log.warn("Failed to send stateless SSE unexpected-chunk error event", io);
    } finally {
      subscription.emitter().complete();
      subscription.clientHandle().close();
    }
  }

  private void handleStatelessStreamError(
      StatelessStreamSubscription subscription,
      Throwable error,
      long streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      ChatMetrics.StreamSample streamSample) {
    StreamErrorHandler.StreamErrorDetails details =
        StreamErrorHandler.describe(
            subscription.provider(), subscription.modelId(), streamedTextUtf8Bytes, error);
    log.warn(
        "Stateless streaming error type={} provider={} model={} statusCode={} receivedBytes={} providerMessage={}",
        details.errorType(),
        details.provider().name(),
        details.modelId(),
        details.statusCode(),
        details.receivedBytes(),
        StreamErrorHandler.extractProviderMessage(error));
    chatUsageRecorder.tokenUsage(lastUsage.get());
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      subscription.emitter().send(
          SseEmitter.event()
              .name(EVENT_ERROR)
              .data(
                  new StreamEventDTO(
                      EVENT_ERROR,
                      StreamErrorHandler.buildClientMessage(details),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      details.provider().name(),
                      details.modelId(),
                      details.statusCode(),
                      details.errorType(),
                      details.receivedBytes(),
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      log.warn("Failed to send stateless SSE error event", e);
    } finally {
      subscription.quotaReservation().rollback();
      chatUsageRecorder.recordRequestMetric(subscription.provider(), subscription.modelId(), Plan.FREE, false);
      chatMetrics.recordStreamEnd(streamSample);
      subscription.emitter().complete();
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
    } catch (IOException e) {
      log.warn("Failed to send stateless SSE done event", e);
      subscription.emitter().complete();
    } finally {
      chatUsageRecorder.recordRequestMetric(subscription.provider(), subscription.modelId(), Plan.FREE, true);
      chatMetrics.recordStreamEnd(streamSample);
    }
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
