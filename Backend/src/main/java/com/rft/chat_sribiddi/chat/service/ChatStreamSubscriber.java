package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
                      chunk,
                      fullText,
                      streamedTextUtf8Bytes,
                      lastUsage,
                      subscription.emitter()),
              error -> handleStreamError(subscription, error, streamedTextUtf8Bytes.get(),
                  lastUsage, streamSample),
              () -> handleStreamCompletion(subscription, fullText, lastUsage, streamSample));
    } catch (RuntimeException | Error error) {
      chatMetrics.recordStreamEnd(streamSample);
      throw error;
    }

    subscription.emitter().onTimeout(
        () -> {
          chatMetrics.recordStreamEnd(streamSample);
          subscription.clientHandle().close();
        });
    subscription.emitter().onError(
        _ -> {
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
      SseEmitter emitter) {
    try {
      String text =
          Optional.ofNullable(chunk)
              .map(ChatResponse::getResult)
              .map(Generation::getOutput)
              .map(AbstractMessage::getText)
              .orElse(null);

      if (text != null && !text.isEmpty()) {
        fullText.append(text);
        streamedTextUtf8Bytes.addAndGet(utf8Length(text));
        emitter.send(
            SseEmitter.event()
                .name("text")
                .data(
                    new StreamEventDTO(
                        "text", text, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                    MediaType.APPLICATION_JSON));
      }

      String reasoning = extractReasoningContent(chunk);
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

      extractUsage(chunk).ifPresent(lastUsage::set);
    } catch (IOException e) {
      log.warn("Failed to send SSE event", e);
    } catch (Exception e) {
      log.warn("Unexpected error processing stream chunk", e);
    }
  }

  private Optional<Usage> extractUsage(ChatResponse response) {
    if (response == null) return Optional.empty();
    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata == null) return Optional.empty();
    return Optional.ofNullable(metadata.getUsage());
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
    ProviderClientHandle clientHandle = subscription.clientHandle();
    try (clientHandle) {
      String assistantResponse = fullText.toString();
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
        sendChatDeletedError(subscription.emitter());
        return;
      }

      if (!saveStreamedAssistantMessage(
          assistantMessage, subscription.chatId(), subscription.emitter())) {
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
      subscription.emitter().complete();
    } catch (IOException e) {
      log.warn("Failed to send SSE done event", e);
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
              true));
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

  private static long utf8Length(CharSequence value) {
    long byteLength = 0;
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current <= 0x7F) {
        byteLength++;
      } else if (current <= 0x7FF) {
        byteLength += 2;
      } else if (Character.isHighSurrogate(current)) {
        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
          byteLength += 4;
          i++;
        } else {
          byteLength += 3;
        }
      } else {
        byteLength += 3;
      }
    }
    return byteLength;
  }

  private String extractReasoningContent(ChatResponse chunk) {
    return Optional.ofNullable(chunk)
        .map(ChatResponse::getResult)
        .map(Generation::getOutput)
        .map(AbstractMessage::getMetadata)
        .map(metadata -> metadata.get("reasoningContent"))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(s -> !s.isEmpty())
        .orElse(null);
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
      ToolExecutionCollector toolExecutionCollector) {}
}
