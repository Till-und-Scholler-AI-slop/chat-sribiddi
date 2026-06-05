package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.access.AccessRestrictionException;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ResolvedProviderConnection;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayChatRequest;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayMessage;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayRuntimeOptions;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayStreamEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayChatService {

  private static final String GATEWAY_CONTEXT = "Provider Gateway connection";

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ChatMessageService chatMessageService;
  private final ChatProperties chatProperties;
  private final UserSettingsService userSettingsService;
  private final CredentialAccessService credentialAccessService;
  private final ProviderConnectionService providerConnectionService;
  private final ProviderCatalogService providerCatalogService;
  private final ProviderGatewayClient providerGatewayClient;
  private final StreamConcurrencyGate streamConcurrencyGate;

  public SendMessageResponseDTO sendMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    GatewayPreparation prep = prepare(chatId, userId, requestDTO);
    GatewayAccumulation result = callGateway(prep.gatewayRequest());
    MessageEntity assistant = persistAssistant(chatId, result.fullText(), result.tokenUsage());
    return new SendMessageResponseDTO(
        result.fullText(),
        result.tokenUsage().promptTokens(),
        result.tokenUsage().generationTokens(),
        result.tokenUsage().totalTokens(),
        prep.updatedTitle());
  }

  public SseEmitter streamMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    GatewayPreparation prep = prepare(chatId, userId, requestDTO);
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    streamConcurrencyGate.acquire(userId);
    CompletableFuture.runAsync(() -> streamGateway(chatId, requestDTO, prep, emitter));
    emitter.onTimeout(() -> streamConcurrencyGate.release(userId));
    emitter.onError(_ -> streamConcurrencyGate.release(userId));
    emitter.onCompletion(() -> streamConcurrencyGate.release(userId));
    return emitter;
  }

  private GatewayPreparation prepare(UUID chatId, String userId, RequestDTO requestDTO) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));
    String providerId = requireGatewayProviderId(requestDTO);
    ResolvedProviderConnection connection =
        providerConnectionService
            .resolveReadyConnection(userId, providerId, requestDTO.connectionId())
            .orElseThrow(
                () ->
                    new AccessRestrictionException(
                        AccessRestrictionException.Reason.API_KEY_REQUIRED,
                        "Connect %s in Settings before using this model".formatted(providerId)));
    if (!connection.hasGatewayEndpoint()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Provider connection requires an OpenAI-compatible or local endpoint URL");
    }
    if (requestDTO.media() != null && !requestDTO.media().isEmpty()) {
      throw new IllegalArgumentException("Gateway providers do not support media attachments yet");
    }
    MessageEntity userMessage =
        chatMessageService.persistUserMessage(chatId, requestDTO.content(), null);
    String updatedTitle = chatMessageService.autoTitleChatIfNeeded(chat, requestDTO.content());
    List<GatewayMessage> messages = gatewayMessages(chatId, userId, requestDTO, userMessage);
    return new GatewayPreparation(
        updatedTitle,
        new GatewayChatRequest(
            providerId,
            requestDTO.modelId(),
            messages,
            connection.authMethod().name(),
            connection.apiKey(),
            connection.baseUrl(),
            connection.headers(),
            connection.queryParams(),
            gatewayRuntimeOptions(requestDTO.options())));
  }

  private String requireGatewayProviderId(RequestDTO requestDTO) {
    String providerId = requestDTO.providerId();
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalArgumentException("providerId is required for gateway providers");
    }
    String canonical = providerCatalogService.canonicalProviderId(providerId);
    var descriptor =
        providerCatalogService
            .find(canonical)
            .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));
    if (!descriptor.chatProvider()) {
      throw new IllegalArgumentException("Provider is not a chat model provider: " + canonical);
    }
    if (!"gateway".equals(descriptor.execution())) {
      throw new IllegalArgumentException(
          "Native provider %s must use the native provider field".formatted(canonical));
    }
    return canonical;
  }

  private List<GatewayMessage> gatewayMessages(
      UUID chatId, String userId, RequestDTO requestDTO, MessageEntity currentUserMessage) {
    List<GatewayMessage> messages = new ArrayList<>();
    messages.add(new GatewayMessage("system", instructionContent(userId)));
    int contextLimit =
        Math.max(
            1,
            Math.min(
                chatProperties.getContextMessageLimit(),
                credentialAccessService.authenticatedLimits().contextMessageLimit()));
    int historyLimit = Math.max(0, contextLimit - 1);
    List<MessageEntity> history =
        historyLimit == 0
            ? List.of()
            : messageRepository
                .findContextMessagesBefore(
                    chatId, currentUserMessage.getId(), PageRequest.of(0, historyLimit))
                .reversed();
    for (MessageEntity message : history) {
      toGatewayMessage(message).ifPresent(messages::add);
    }
    messages.add(new GatewayMessage("user", requestDTO.content()));
    return messages;
  }

  private java.util.Optional<GatewayMessage> toGatewayMessage(MessageEntity message) {
    if (message.getMessage() == null || message.getMessage().isBlank()) {
      return java.util.Optional.empty();
    }
    String role =
        switch (message.getRole()) {
          case ASSISTANT -> "assistant";
          case SYSTEM -> "system";
          default -> "user";
        };
    return java.util.Optional.of(new GatewayMessage(role, message.getMessage()));
  }

  private String instructionContent(String userId) {
    return userSettingsService
        .getCustomInstructions(userId)
        .filter(value -> !value.isBlank())
        .map(value -> chatProperties.getSystemPrompt() + "\n\n" + value)
        .orElse(chatProperties.getSystemPrompt());
  }

  private GatewayRuntimeOptions gatewayRuntimeOptions(RequestDTO.ChatRuntimeOptions options) {
    if (options == null) {
      return null;
    }
    return new GatewayRuntimeOptions(
        options.temperature(),
        options.topP(),
        options.topK(),
        options.maxTokens(),
        options.frequencyPenalty(),
        options.presencePenalty(),
        options.stopSequences());
  }

  private GatewayAccumulation callGateway(GatewayChatRequest request) {
    StringBuilder fullText = new StringBuilder();
    TokenAccumulator tokens = new TokenAccumulator();
    providerGatewayClient.streamChat(
        request,
        event -> {
          if ("text".equals(event.type()) && event.content() != null) {
            fullText.append(event.content());
          }
          if ("done".equals(event.type())) {
            tokens.update(event);
          }
          if ("error".equals(event.type())) {
            throw new ProviderGatewayClient.ProviderGatewayException(
                event.content() == null ? "Provider gateway returned an error" : event.content());
          }
        });
    if (fullText.isEmpty()) {
      throw new ProviderGatewayClient.ProviderGatewayException("Provider returned an empty response");
    }
    return new GatewayAccumulation(fullText.toString(), tokens.toUsage());
  }

  private void streamGateway(
      UUID chatId, RequestDTO requestDTO, GatewayPreparation prep, SseEmitter emitter) {
    StringBuilder fullText = new StringBuilder();
    TokenAccumulator tokens = new TokenAccumulator();
    try {
      providerGatewayClient.streamChat(
          prep.gatewayRequest(),
          event -> handleGatewayStreamEvent(requestDTO, emitter, fullText, tokens, event));
      if (fullText.isEmpty()) {
        sendGatewayError(emitter, requestDTO, "Provider returned an empty response", "EMPTY_RESPONSE", 0L);
        return;
      }
      MessageEntity assistant = persistAssistant(chatId, fullText.toString(), tokens.toUsage());
      emitter.send(
          SseEmitter.event()
              .name("done")
              .data(
                  new StreamEventDTO(
                      "done",
                      null,
                      tokens.toUsage().promptTokens(),
                      tokens.toUsage().generationTokens(),
                      tokens.toUsage().totalTokens(),
                      prep.updatedTitle(),
                      assistant.getId(),
                      false,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null),
                  MediaType.APPLICATION_JSON));
      emitter.complete();
    } catch (Exception e) {
      sendGatewayError(
          emitter,
          requestDTO,
          e.getMessage() == null ? "Provider gateway request failed" : e.getMessage(),
          "GATEWAY_ERROR",
          (long) fullText.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
  }

  private void handleGatewayStreamEvent(
      RequestDTO requestDTO,
      SseEmitter emitter,
      StringBuilder fullText,
      TokenAccumulator tokens,
      GatewayStreamEvent event) {
    try {
      if ("text".equals(event.type()) && event.content() != null && !event.content().isEmpty()) {
        fullText.append(event.content());
        emitter.send(
            SseEmitter.event()
                .name("text")
                .data(
                    new StreamEventDTO(
                        "text", event.content(), null, null, null, null, null, null,
                        null, null, null, null, null, null),
                    MediaType.APPLICATION_JSON));
      } else if ("done".equals(event.type())) {
        tokens.update(event);
      } else if ("error".equals(event.type())) {
        throw new ProviderGatewayClient.ProviderGatewayException(
            event.content() == null ? "Provider gateway returned an error" : event.content());
      }
    } catch (IOException e) {
      throw new ProviderGatewayClient.ProviderGatewayException("Failed to send gateway SSE event", e);
    }
  }

  private MessageEntity persistAssistant(UUID chatId, String message, TokenUsage usage) {
    MessageEntity assistant = new MessageEntity();
    assistant.setChatId(chatId);
    assistant.setMessage(message);
    assistant.setRole(Role.ASSISTANT);
    assistant.setContext(GATEWAY_CONTEXT);
    assistant.setCreatedAt(Instant.now());
    assistant.setPromptTokens(usage.promptTokens());
    assistant.setGenerationTokens(usage.generationTokens());
    assistant.setTotalTokens(usage.totalTokens());
    return chatMessageService.saveMessageOrThrowIfChatMissing(assistant);
  }

  private void sendGatewayError(
      SseEmitter emitter, RequestDTO requestDTO, String message, String errorType, Long receivedBytes) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("error")
              .data(
                  new StreamEventDTO(
                      "error",
                      message,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      requestDTO.providerId(),
                      requestDTO.modelId(),
                      null,
                      errorType,
                      receivedBytes,
                      true),
                  MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      log.debug("Failed to send gateway error SSE event");
    } finally {
      emitter.complete();
    }
  }

  private record GatewayPreparation(String updatedTitle, GatewayChatRequest gatewayRequest) {}

  private record GatewayAccumulation(String fullText, TokenUsage tokenUsage) {}

  private record TokenUsage(long promptTokens, long generationTokens, long totalTokens) {}

  private static final class TokenAccumulator {
    private long promptTokens;
    private long generationTokens;
    private long totalTokens;

    void update(GatewayStreamEvent event) {
      promptTokens = valueOrZero(event.promptTokens());
      generationTokens = valueOrZero(event.generationTokens());
      totalTokens = valueOrZero(event.totalTokens());
    }

    TokenUsage toUsage() {
      return new TokenUsage(promptTokens, generationTokens, totalTokens);
    }

    private long valueOrZero(Integer value) {
      return value == null ? 0L : value.longValue();
    }
  }
}
