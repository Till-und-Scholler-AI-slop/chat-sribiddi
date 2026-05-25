package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.GuestQuotaService;
import com.rft.chat_sribiddi.billing.GuestQuotaService.GuestQuotaReservation;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.chat.dto.AnonymousChatRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.StatelessChatMessage;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.StatelessPromptMessageRequest;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousChatService {

  private static final String EVENT_ERROR = "error";

  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;
  private final MediaValidationService mediaValidationService;
  private final PromptMessageBuilder promptMessageBuilder;
  private final BillingModeResolver billingModeResolver;
  private final EntitlementService entitlementService;
  private final ChatUsageRecorder chatUsageRecorder;
  private final GuestQuotaService guestQuotaService;
  private final ChatMetrics chatMetrics;

  public SseEmitter streamMessage(
      AnonymousChatRequestDTO request, String guestId, HttpServletRequest servletRequest) {
    if (request.provider() != Provider.OPENROUTER) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          Plan.FREE,
          "Anonymous chat is only available through OpenRouter managed models");
    }

    Entitlements entitlements = entitlementService.get(null);
    BillingModeResolver.ResolvedKey resolvedKey =
        billingModeResolver.resolve(null, request.provider(), request.modelId(), BillingMode.MANAGED);
    List<MediaAttachment> validatedMedia =
        mediaValidationService.validateAttachments(request.media(), entitlements);
    List<StatelessChatMessage> history = toHistory(request.history());
    List<org.springframework.ai.chat.messages.Message> promptMessages =
        promptMessageBuilder.buildStateless(
            new StatelessPromptMessageRequest(
                history,
                request.content(),
                validatedMedia,
                null,
                request.provider(),
                request.modelId(),
                entitlements,
                null));

    GuestQuotaReservation quotaReservation =
        guestQuotaService.reserveDailyCall(servletRequest, guestId);
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    ProviderClientHandle clientHandle = null;
    ChatMetrics.StreamSample streamSample = null;
    try {
      clientHandle =
          providerClientFactory.getClient(
              request.provider(), resolvedKey.apiKey(), resolvedKey.effectiveMode());
      streamSample = chatMetrics.startStream(request.provider(), request.modelId());
      var options =
          providerClientFactory.buildOptions(request.provider(), request.modelId(), resolvedKey.apiKey());
      var promptSpec =
          clientHandle.client().prompt().options(options.mutate()).messages(promptMessages);
      subscribe(
          promptSpec,
          emitter,
          clientHandle,
          request,
          quotaReservation,
          streamSample);
    } catch (RuntimeException | Error error) {
      quotaReservation.rollback();
      chatMetrics.recordStreamEnd(streamSample);
      if (clientHandle != null) {
        clientHandle.close();
      }
      throw error;
    }
    return emitter;
  }

  private void subscribe(
      org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec promptSpec,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      AnonymousChatRequestDTO request,
      GuestQuotaReservation quotaReservation,
      ChatMetrics.StreamSample streamSample) {
    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    UUID streamId = UUID.randomUUID();

    promptSpec
        .stream()
        .chatResponse()
        .subscribe(
            chunk ->
                handleStreamChunk(
                    chunk, fullText, streamedTextUtf8Bytes, lastUsage, emitter, quotaReservation,
                    clientHandle, streamSample),
            error ->
                handleStreamError(
                    request, streamId, error, streamedTextUtf8Bytes.get(), lastUsage, emitter,
                    clientHandle, quotaReservation, streamSample),
            () ->
                handleStreamCompletion(
                    request, lastUsage, emitter, clientHandle, quotaReservation, streamSample));

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

  private void handleStreamChunk(
      ChatResponse chunk,
      StringBuilder fullText,
      AtomicLong streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      SseEmitter emitter,
      GuestQuotaReservation quotaReservation,
      ProviderClientHandle clientHandle,
      ChatMetrics.StreamSample streamSample) {
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
      log.warn("Failed to send anonymous SSE event", e);
    } catch (Exception e) {
      log.warn("Unexpected error processing anonymous stream chunk", e);
      sendUnexpectedChunkError(emitter, quotaReservation, clientHandle, streamSample);
    }
  }

  private void sendUnexpectedChunkError(
      SseEmitter emitter,
      GuestQuotaReservation quotaReservation,
      ProviderClientHandle clientHandle,
      ChatMetrics.StreamSample streamSample) {
    quotaReservation.rollback();
    try {
      emitter.send(
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
      log.warn("Failed to send anonymous SSE unexpected-chunk error event", io);
    } finally {
      chatMetrics.recordStreamEnd(streamSample);
      clientHandle.close();
      emitter.complete();
    }
  }

  private void handleStreamCompletion(
      AnonymousChatRequestDTO request,
      AtomicReference<Usage> lastUsage,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      GuestQuotaReservation quotaReservation,
      ChatMetrics.StreamSample streamSample) {
    try (clientHandle) {
      TokenUsage tokenUsage = chatUsageRecorder.tokenUsage(lastUsage.get());
      quotaReservation.commit();
      emitter.send(
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
      emitter.complete();
    } catch (IOException e) {
      log.warn("Failed to send anonymous SSE done event", e);
      emitter.complete();
    } finally {
      chatUsageRecorder.recordRequestMetric(request.provider(), request.modelId(), Plan.FREE, true);
      chatMetrics.recordStreamEnd(streamSample);
    }
  }

  private void handleStreamError(
      AnonymousChatRequestDTO request,
      UUID streamId,
      Throwable error,
      long streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      SseEmitter emitter,
      ProviderClientHandle clientHandle,
      GuestQuotaReservation quotaReservation,
      ChatMetrics.StreamSample streamSample) {
    StreamErrorHandler.StreamErrorDetails details =
        StreamErrorHandler.describe(request.provider(), request.modelId(), streamedTextUtf8Bytes, error);
    log.warn(
        "Anonymous streaming error type={} provider={} model={} streamId={} statusCode={} receivedBytes={} providerMessage={}",
        details.errorType(),
        details.provider().name(),
        details.modelId(),
        streamId,
        details.statusCode(),
        details.receivedBytes(),
        StreamErrorHandler.extractProviderMessage(error));
    chatUsageRecorder.tokenUsage(lastUsage.get());
    try (clientHandle) {
      emitter.send(
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
      log.warn("Failed to send anonymous SSE error event", e);
    } finally {
      quotaReservation.rollback();
      chatUsageRecorder.recordRequestMetric(request.provider(), request.modelId(), Plan.FREE, false);
      chatMetrics.recordStreamEnd(streamSample);
      emitter.complete();
    }
  }

  private List<StatelessChatMessage> toHistory(List<AnonymousChatRequestDTO.HistoryMessage> history) {
    if (history == null || history.isEmpty()) {
      return List.of();
    }
    return history.stream()
        .map(item -> new StatelessChatMessage(parseRole(item.role()), item.message()))
        .toList();
  }

  private Role parseRole(String raw) {
    String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "USER" -> Role.USER;
      case "ASSISTANT" -> Role.ASSISTANT;
      default -> throw new IllegalArgumentException("Unsupported anonymous history role: " + raw);
    };
  }

  private Optional<Usage> extractUsage(ChatResponse response) {
    if (response == null) return Optional.empty();
    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata == null) return Optional.empty();
    return Optional.ofNullable(metadata.getUsage());
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
}
