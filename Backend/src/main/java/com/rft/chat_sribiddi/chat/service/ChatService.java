package com.rft.chat_sribiddi.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.dto.ToolCallLogResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import com.rft.chat_sribiddi.chat.toolcall.LoggedBraveSearchTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedCalculatorTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedCurrentDateTimeTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedPythonInterpreterTool;
import com.rft.chat_sribiddi.chat.toolcall.LoggedUrlFetcherTool;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.UsageEventService;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.observability.ChatMetrics;
import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private static final String SYSTEM_PROMPT =
      "You are a helpful assistant. Your answer must be structured in markdown.";

  private static final String EVENT_ERROR = "error";

  private static final String ATTACHMENTS_PREFIX = "attachments:";

  private static final String NO_API_KEY_MESSAGE =
      "No API key supplied and no stored key found for provider %s";

  private static final String CHAT_FK_CONSTRAINT = "messages_chat_id_fkey";
  private static final int MIN_CONTEXT_EXCERPT_CANDIDATE_LENGTH = 8;
  private static final int MAX_CONTEXT_EXCERPT_MATCHES = 5;
  private static final int MAX_CONTEXT_EXCERPT_LENGTH = 2_000;

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ToolCallLogRepository toolCallLogRepository;
  private final ChatToolRepository chatToolRepository;
  private final ApiKeyService apiKeyService;
  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;
  private final UserSettingsService userSettingsService;
  private final WebSearchProperties webSearchProperties;
  private final HttpClient webSearchHttpClient;
  private final ToolRegistry toolRegistry;
  private final CalculatorTool calculatorTool;
  private final UrlFetcherTool urlFetcherTool;
  private final PythonInterpreterTool pythonInterpreterTool;
  private final com.rft.chat_sribiddi.tools.CurrentDateTimeTool currentDateTimeTool;
  private final EntitlementService entitlementService;
  private final UsageEventService usageEventService;
  private final ChatMetrics chatMetrics;

  @Transactional
  public void deleteChat(UUID chatId, String userId) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    messageRepository.deleteByChatId(chat.getChatId());
    chatToolRepository.deleteByChatId(chat.getChatId());
    chatRepository.deleteById(chat.getChatId());
  }

  @Transactional
  public ChatEntity createChat(ChatEntity chatEntity, String userId) {
    chatEntity.setUserId(userId);
    chatEntity.setTitle(normalizeChatTitle(chatEntity.getTitle()));
    return chatRepository.save(chatEntity);
  }

  public List<ChatEntity> listChats(String userId, Integer limit) {
    return listChats(userId, limit, null);
  }

  public List<ChatEntity> listChats(String userId, Integer limit, Integer offset) {
    int pageSize =
        (limit != null && limit > 0)
            ? Math.min(limit, chatProperties.getChatPageSize())
            : chatProperties.getChatPageSize();
    return chatRepository.findByUserIdOrderByCreatedAtDesc(
        userId, OffsetPageRequest.of(pageSize, offset));
  }

  public List<MessageEntity> listMessages(UUID chatId, String userId, Integer limit) {
    return listMessages(chatId, userId, limit, null);
  }

  public List<MessageEntity> listMessages(UUID chatId, String userId, Integer limit, Integer offset) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));
    int pageSize =
        (limit != null && limit > 0)
            ? Math.min(limit, chatProperties.getMessagePageSize())
            : chatProperties.getMessagePageSize();
    return messageRepository
        .findByChatIdOrderByCreatedAtDesc(chatId, OffsetPageRequest.of(pageSize, offset))
        .reversed();
  }

  public List<MessageResponseDTO> listMessageResponses(UUID chatId, String userId, Integer limit) {
    return listMessageResponses(chatId, userId, limit, null);
  }

  public List<MessageResponseDTO> listMessageResponses(
      UUID chatId, String userId, Integer limit, Integer offset) {
    List<MessageEntity> messages = listMessages(chatId, userId, limit, offset);
    if (messages.isEmpty()) {
      return List.of();
    }
    List<Long> messageIds =
        messages.stream().map(MessageEntity::getId).filter(Objects::nonNull).toList();
    Set<Long> messageIdsWithToolCalls =
        (messageIds.isEmpty() ? List.<Long>of() : toolCallLogRepository.findMessageIdsWithToolCalls(messageIds))
            .stream()
            .collect(Collectors.toSet());
    return messages.stream()
        .map(
            message ->
                MessageResponseDTO.from(
                    message, messageIdsWithToolCalls.contains(message.getId())))
        .toList();
  }

  public List<ToolCallLogResponseDTO> listToolCallLogs(Long messageId, String userId) {
    MessageEntity message =
        messageRepository
            .findById(messageId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Message not found: " + messageId));
    chatRepository
        .findByChatIdAndUserId(message.getChatId(), userId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Message not found: " + messageId));
    return toolCallLogRepository.findByMessageIdOrderByStartedAtAsc(messageId).stream()
        .map(ToolCallLogResponseDTO::from)
        .toList();
  }

  @Transactional
  public ChatEntity updateChatTitle(UUID chatId, String userId, String title) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    chat.setTitle(title.trim());
    return chatRepository.save(chat);
  }

  public SendMessageResponseDTO sendMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    BillingMode billingMode = assertEntitlementsForCall(userId, requestDTO);
    MessagePreparation prep = prepareUserMessage(chatId, chat, userId, requestDTO);
    if (log.isTraceEnabled()) {
      log.trace("Prompt messages: {}", prep.promptMessages());
    }

    String resolvedKey = resolveApiKey(userId, requestDTO);
    Plan plan = resolvePlanForMetrics(userId);

    ChatResponse chatResponse;
    ToolExecutionCollector toolExecutionCollector = new ToolExecutionCollector();
    boolean providerCallSucceeded = false;
    try (ClientHolder clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedKey)) {
      var options =
          providerClientFactory.buildOptions(
              requestDTO.provider(), requestDTO.modelId(), resolvedKey);
      var client = clientHolder.client();
      var promptSpec = client.prompt().options(options).messages(prep.promptMessages());
      wireTools(
          chatId,
          userId,
          requestDTO.provider(),
          requestDTO.modelId(),
          requestDTO.webSearchEnabled(),
          toolExecutionCollector,
          promptSpec);
      chatResponse = promptSpec.call().chatResponse();
      providerCallSucceeded = true;
    } catch (RuntimeException | Error error) {
      chatMetrics.recordRequest(requestDTO.provider(), requestDTO.modelId(), plan, "error");
      throw error;
    }

    if (log.isTraceEnabled()) {
      log.trace("Chat response: {}", chatResponse);
    }

    String assistantResponse =
        Optional.ofNullable(chatResponse)
            .map(ChatResponse::getResult)
            .map(Generation::getOutput)
            .map(AbstractMessage::getText)
            .orElse("");

    Long promptTokens = null;
    Long generationTokens = null;
    Long totalTokens = null;

    Optional<Usage> usageOpt = extractUsage(chatResponse);
    if (usageOpt.isPresent()) {
      Usage usage = usageOpt.get();
      promptTokens = safeLong(usage.getPromptTokens());
      generationTokens = safeLong(usage.getCompletionTokens());
      totalTokens = safeLong(usage.getTotalTokens());
    }

    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setChatId(chatId);
    assistantMessage.setMessage(assistantResponse);
    assistantMessage.setRole(Role.ASSISTANT);
    assistantMessage.setContext(
        buildAssistantContext(
            prep.promptMessages().size(),
            supportsSystemInstruction(requestDTO.provider(), requestDTO.modelId())));
    assistantMessage.setCreatedAt(Instant.now());
    assistantMessage.setPromptTokens(promptTokens);
    assistantMessage.setGenerationTokens(generationTokens);
    assistantMessage.setTotalTokens(totalTokens);
    saveMessageOrThrowIfChatMissing(assistantMessage);
    persistToolCallLogs(assistantMessage, toolExecutionCollector, assistantResponse);
    recordUsageAndMaybeIncrementCounter(
        userId,
        requestDTO.provider(),
        requestDTO.modelId(),
        promptTokens,
        generationTokens,
        totalTokens,
        assistantMessage.getId(),
        billingMode,
        plan,
        providerCallSucceeded);

    log.debug("Message persisted successfully");

    return new SendMessageResponseDTO(assistantResponse, promptTokens, generationTokens, totalTokens, prep.updatedTitle());
  }

  /**
   * Streams a chat response as Server-Sent Events. The user message is persisted before the stream
   * begins; the assistant message is persisted once the stream completes.
   */
  public SseEmitter streamMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    BillingMode billingMode = assertEntitlementsForCall(userId, requestDTO);
    Plan plan = resolvePlanForMetrics(userId);
    MessagePreparation prep = prepareUserMessage(chatId, chat, userId, requestDTO);

    return createAndSubscribeStream(
        userId, requestDTO, prep.promptMessages(), chatId, prep.updatedTitle(), billingMode, plan);
  }

  /**
   * Edits an existing user message and streams a new AI response.
   * All messages after the edited message are deleted before streaming begins.
   */
  @Transactional
  public SseEmitter editAndStreamMessage(
      UUID chatId, String userId, Long messageId, RequestDTO requestDTO) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));

    MessageEntity userMessage =
        messageRepository
            .findByIdAndChatId(messageId, chatId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Message %d not found in chat %s".formatted(messageId, chatId)));

    if (userMessage.getRole() != Role.USER) {
      throw new IllegalArgumentException("Only user messages can be edited.");
    }

    List<MediaAttachment> validatedMedia = validateMediaAttachments(requestDTO.media());
    String userContext = buildMediaContext(validatedMedia);

    // Run entitlement checks before mutating chat history so that a rejected request
    // never leaves the database in a half-edited state.
    BillingMode billingMode = assertEntitlementsForCall(userId, requestDTO);
    Plan plan = resolvePlanForMetrics(userId);

    userMessage.setMessage(requestDTO.content());
    userMessage.setContext(userContext);
    messageRepository.save(userMessage);

    messageRepository.deleteByIdGreaterThanAndChatId(chatId, messageId);

    String customInstructions =
        userSettingsService.getCustomInstructions(userId).orElse(null);
    List<Message> promptMessages =
        buildPromptMessages(chatId, validatedMedia, messageId, customInstructions,
            requestDTO.provider(), requestDTO.modelId());

    return createAndSubscribeStream(
        userId, requestDTO, promptMessages, chatId, null, billingMode, plan);
  }

  private record MessagePreparation(
      String updatedTitle,
      List<Message> promptMessages) {}

  private String buildMediaContext(List<MediaAttachment> validatedMedia) {
    return validatedMedia.isEmpty()
        ? null
        : ATTACHMENTS_PREFIX
            + validatedMedia.stream()
                .map(RequestDTO.MediaAttachment::mimeType)
                .collect(Collectors.joining(","));
  }

  private String resolveApiKey(String userId, RequestDTO requestDTO) {
    return apiKeyService
        .getKey(userId, requestDTO.provider())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    NO_API_KEY_MESSAGE.formatted(requestDTO.provider())));
  }

  private MessagePreparation prepareUserMessage(
      UUID chatId, ChatEntity chat, String userId, RequestDTO requestDTO) {
    List<MediaAttachment> validatedMedia = validateMediaAttachments(requestDTO.media());
    String userContext = buildMediaContext(validatedMedia);
    MessageEntity persistedUserMessage = persistMessage(chatId, requestDTO.content(), userContext);
    String updatedTitle = autoTitleChatIfNeeded(chat, requestDTO.content());
    String customInstructions = userSettingsService.getCustomInstructions(userId).orElse(null);
    List<Message> promptMessages =
        buildPromptMessages(chatId, validatedMedia, persistedUserMessage.getId(), customInstructions,
            requestDTO.provider(), requestDTO.modelId());
    return new MessagePreparation(updatedTitle, promptMessages);
  }

  private SseEmitter createAndSubscribeStream(
      String userId, RequestDTO requestDTO, List<Message> promptMessages,
      UUID chatId, String updatedTitle, BillingMode billingMode, Plan plan) {
    String resolvedKey = resolveApiKey(userId, requestDTO);
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    ClientHolder clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedKey);
    try {
      var options =
          providerClientFactory.buildOptions(
              requestDTO.provider(), requestDTO.modelId(), resolvedKey);
      var client = clientHolder.client();
      var promptSpec = client.prompt().options(options).messages(promptMessages);
      ToolExecutionCollector toolExecutionCollector = new ToolExecutionCollector();
      wireTools(
          chatId,
          userId,
          requestDTO.provider(),
          requestDTO.modelId(),
          requestDTO.webSearchEnabled(),
          toolExecutionCollector,
          promptSpec);
      String assistantContext =
          buildAssistantContext(
              promptMessages.size(),
              supportsSystemInstruction(requestDTO.provider(), requestDTO.modelId()));
      subscribeToStream(
          promptSpec,
          emitter,
          clientHolder,
          userId,
          requestDTO.provider(),
          requestDTO.modelId(),
          billingMode,
          plan,
          chatId,
          assistantContext,
          updatedTitle,
          toolExecutionCollector);
    } catch (RuntimeException | Error error) {
      clientHolder.close();
      throw error;
    }
    return emitter;
  }

  /**
   * Subscribes to the streaming response and wires up SSE event emission, assistant message
   * persistence, and resource cleanup. Shared by {@code streamMessage} and {@code editAndStreamMessage}.
   */
  private void subscribeToStream(
      ChatClient.ChatClientRequestSpec promptSpec,
      SseEmitter emitter,
      ClientHolder clientHolder,
      String userId,
      Provider provider,
      String modelId,
      BillingMode billingMode,
      Plan plan,
      UUID chatId,
      String assistantContext,
      String updatedTitle,
      ToolExecutionCollector toolExecutionCollector) {

    StringBuilder fullText = new StringBuilder();
    AtomicLong streamedTextUtf8Bytes = new AtomicLong();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();
    ChatMetrics.StreamSample streamSample = chatMetrics.startStream(provider, modelId);

    try {
      promptSpec
          .stream()
          .chatResponse()
          .subscribe(
              chunk -> handleStreamChunk(chunk, fullText, streamedTextUtf8Bytes, lastUsage, emitter),
              error ->
                  handleStreamError(
                      userId,
                      provider,
                      modelId,
                      billingMode,
                      plan,
                      chatId,
                      error,
                      streamedTextUtf8Bytes.get(),
                      lastUsage,
                      emitter,
                      clientHolder,
                      streamSample),
              () -> handleStreamCompletion(
                  userId,
                  provider,
                  modelId,
                  billingMode,
                  plan,
                  fullText,
                  lastUsage,
                  chatId,
                  assistantContext,
                  updatedTitle,
                  toolExecutionCollector,
                  emitter,
                  clientHolder,
                  streamSample));
    } catch (RuntimeException | Error error) {
      chatMetrics.recordStreamEnd(streamSample);
      throw error;
    }

    emitter.onTimeout(
        () -> {
          chatMetrics.recordStreamEnd(streamSample);
          clientHolder.close();
        });
    emitter.onError(
        _ -> {
          chatMetrics.recordStreamEnd(streamSample);
          clientHolder.close();
        });
    emitter.onCompletion(() -> chatMetrics.recordStreamEnd(streamSample));
  }

  private void handleStreamChunk(
      ChatResponse chunk, StringBuilder fullText,
      AtomicLong streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage, SseEmitter emitter) {
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
      String userId,
      Provider provider,
      String modelId,
      BillingMode billingMode,
      Plan plan,
      UUID chatId,
      Throwable error,
      long streamedTextUtf8Bytes,
      AtomicReference<Usage> lastUsage,
      SseEmitter emitter,
      ClientHolder clientHolder,
      ChatMetrics.StreamSample streamSample) {
    StreamErrorDetails streamErrorDetails =
        describeStreamError(provider, modelId, streamedTextUtf8Bytes, error);
    logStreamingError(chatId, streamErrorDetails, error);
    TokenUsage tokenUsage = toTokenUsage(lastUsage.get());
      try (clientHolder) {
          emitter.send(
                  SseEmitter.event()
                          .name(EVENT_ERROR)
                          .data(
                                    new StreamEventDTO(
                                            EVENT_ERROR,
                                            buildClientStreamErrorMessage(streamErrorDetails),
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
          // Stream did not complete normally — do not increment the managed-mode counter,
          // regardless of whether the error originated from the provider or the client.
          recordUsageAndMaybeIncrementCounter(
              userId,
              provider,
              modelId,
              tokenUsage.promptTokens(),
              tokenUsage.generationTokens(),
              tokenUsage.totalTokens(),
              null,
              billingMode,
              plan,
              false);
          chatMetrics.recordStreamEnd(streamSample);
          emitter.complete();
      }
  }

  private void handleStreamCompletion(
      String userId,
      Provider provider,
      String modelId,
      BillingMode billingMode,
      Plan plan,
      StringBuilder fullText, AtomicReference<Usage> lastUsage,
      UUID chatId, String assistantContext, String updatedTitle,
      ToolExecutionCollector toolExecutionCollector,
      SseEmitter emitter, ClientHolder clientHolder, ChatMetrics.StreamSample streamSample) {
      AtomicReference<Long> savedMessageId = new AtomicReference<>();
      try (clientHolder) {
          String assistantResponse = fullText.toString();
          Usage usage = lastUsage.get();

          Long promptTokens = usage != null ? safeLong(usage.getPromptTokens()) : null;
          Long generationTokens = usage != null ? safeLong(usage.getCompletionTokens()) : null;
          Long totalTokens = usage != null ? safeLong(usage.getTotalTokens()) : null;

          MessageEntity assistantMessage = new MessageEntity();
          assistantMessage.setChatId(chatId);
          assistantMessage.setMessage(assistantResponse);
          assistantMessage.setRole(Role.ASSISTANT);
          assistantMessage.setContext(assistantContext);
          assistantMessage.setCreatedAt(Instant.now());
          assistantMessage.setPromptTokens(promptTokens);
          assistantMessage.setGenerationTokens(generationTokens);
          assistantMessage.setTotalTokens(totalTokens);

          if (!chatRepository.existsById(chatId)) {
              log.warn(
                      "Chat {} was deleted during streaming — skipping assistant message save", chatId);
              sendChatDeletedError(emitter);
              return;
          }

          if (!saveStreamedAssistantMessage(assistantMessage, chatId, emitter)) {
              return;
          }
          savedMessageId.set(assistantMessage.getId());

          persistToolCallLogs(assistantMessage, toolExecutionCollector, assistantResponse);

          emitter.send(
                  SseEmitter.event()
                          .name("done")
                          .data(
           new StreamEventDTO(
                                             "done",
                                             null,
                                            promptTokens,
                                            generationTokens,
                                            totalTokens,
                                            updatedTitle,
                                            assistantMessage.getId(),
                                            !toolExecutionCollector.isEmpty(),
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null),
                                     MediaType.APPLICATION_JSON));
           emitter.complete();
       } catch (IOException e) {
           log.warn("Failed to send SSE done event", e);
           emitter.complete();
       } finally {
           TokenUsage tokenUsage = toTokenUsage(lastUsage.get());
           recordUsageAndMaybeIncrementCounter(
               userId,
               provider,
               modelId,
               tokenUsage.promptTokens(),
               tokenUsage.generationTokens(),
               tokenUsage.totalTokens(),
               savedMessageId.get(),
               billingMode,
               plan,
               true);
           chatMetrics.recordStreamEnd(streamSample);
       }
  }

  private BillingMode assertEntitlementsForCall(String userId, RequestDTO requestDTO) {
    BillingMode billingMode = entitlementService.resolveBillingMode(userId);
    entitlementService.assertCanUseModel(
        userId, requestDTO.provider(), requestDTO.modelId(), billingMode);
    if (billingMode == BillingMode.MANAGED) {
      entitlementService.assertWithinDailyCap(userId);
    }
    return billingMode;
  }

  private Plan resolvePlanForMetrics(String userId) {
    try {
      var entitlements = entitlementService.get(userId);
      return entitlements == null ? null : entitlements.plan();
    } catch (Exception ex) {
      log.debug("Unable to resolve plan for chat metrics user={}", userId, ex);
      return null;
    }
  }

  private void recordUsageAndMaybeIncrementCounter(
      String userId,
      Provider provider,
      String modelId,
      Long promptTokens,
      Long generationTokens,
      Long totalTokens,
      Long messageId,
      BillingMode billingMode,
      Plan plan,
      boolean providerCallSucceeded) {
    chatMetrics.recordRequest(provider, modelId, plan, providerCallSucceeded ? "success" : "error");
    chatMetrics.recordTokens(provider, modelId, "prompt", promptTokens);
    chatMetrics.recordTokens(provider, modelId, "completion", generationTokens);
    try {
      usageEventService.record(
          userId,
          provider,
          modelId,
          promptTokens,
          generationTokens,
          totalTokens,
          messageId,
          billingMode);
    } catch (Exception ex) {
      log.warn("Failed to record usage event for user {}", userId, ex);
    }
    if (billingMode == BillingMode.MANAGED && providerCallSucceeded) {
      try {
        entitlementService.incrementCounter(userId);
      } catch (Exception ex) {
        log.warn("Failed to increment managed usage counter for user {}", userId, ex);
      }
    }
  }

  private TokenUsage toTokenUsage(Usage usage) {
    if (usage == null) {
      return TokenUsage.EMPTY;
    }
    return new TokenUsage(
        safeLong(usage.getPromptTokens()),
        safeLong(usage.getCompletionTokens()),
        safeLong(usage.getTotalTokens()));
  }

  private record TokenUsage(Long promptTokens, Long generationTokens, Long totalTokens) {
    private static final TokenUsage EMPTY = new TokenUsage(null, null, null);
  }

  /**
   * Saves the streamed assistant message. Returns {@code true} on success,
   * {@code false} if the chat was deleted (a chat-deleted error is sent on the emitter).
   */
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

  private void persistToolCallLogs(
      MessageEntity assistantMessage,
      ToolExecutionCollector toolExecutionCollector,
      String assistantResponse) {
    if (assistantMessage.getId() == null || toolExecutionCollector.isEmpty()) {
      return;
    }
    List<ToolCallLogEntity> logs =
        toolExecutionCollector.snapshot().stream()
            .map(
                record -> {
                  ToolCallLogEntity entity = new ToolCallLogEntity();
                  entity.setMessageId(assistantMessage.getId());
                  entity.setToolName(record.toolName());
                  entity.setInputPayload(record.inputPayload());
                  entity.setOutputPayload(record.outputPayload());
                  entity.setStartedAt(record.startedAt());
                  entity.setDurationMs(record.durationMs());
                  entity.setContextExcerpt(
                      extractContextExcerpt(record.outputPayload(), assistantResponse));
                  return entity;
                })
            .toList();
    toolCallLogRepository.saveAll(logs);
  }

  private String extractContextExcerpt(String toolOutput, String assistantResponse) {
    if (toolOutput == null
        || toolOutput.isBlank()
        || assistantResponse == null
        || assistantResponse.isBlank()) {
      return null;
    }
    String normalizedAssistant = normalizeForMatch(assistantResponse);
    if (normalizedAssistant.isEmpty()) {
      return null;
    }
    List<String> matches = new ArrayList<>();
    for (String line : toolOutput.split("\\R+")) {
      String candidate = line.replaceFirst("^\\d+\\.\\s*", "").trim();
      if (candidate.length() < MIN_CONTEXT_EXCERPT_CANDIDATE_LENGTH) {
        continue;
      }
      String normalizedCandidate = normalizeForMatch(candidate);
      if (normalizedCandidate.length() < MIN_CONTEXT_EXCERPT_CANDIDATE_LENGTH) {
        continue;
      }
      if (normalizedAssistant.contains(normalizedCandidate) && !matches.contains(candidate)) {
        matches.add(candidate);
      }
      if (matches.size() >= MAX_CONTEXT_EXCERPT_MATCHES) {
        break;
      }
    }
    if (matches.isEmpty()) {
      return null;
    }
    String excerpt = String.join("\n", matches);
    return excerpt.length() > MAX_CONTEXT_EXCERPT_LENGTH
        ? excerpt.substring(0, MAX_CONTEXT_EXCERPT_LENGTH)
        : excerpt;
  }

  private String normalizeForMatch(String value) {
    return value == null
        ? ""
        : value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{Alnum}]+", " ")
            .trim();
  }

  /**
   * Trims whitespace from the title and truncates it to {@code maxChatTitleLength}.
   * Returns {@code null} if the result would be blank.
   */
  private String normalizeChatTitle(String title) {
    if (title == null) {
      return null;
    }
    String trimmed = title.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int maxLen = Math.max(1, chatProperties.getMaxChatTitleLength());
    return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
  }

  /**
   * Sets the chat title from the first message content if the chat currently has no title.
   *
   * @return the new title if it was auto-set, {@code null} otherwise
   */
  private String autoTitleChatIfNeeded(ChatEntity chat, String messageContent) {
    if (chat.getTitle() != null && !chat.getTitle().isBlank()) {
      return null;
    }
    String title = normalizeChatTitle(messageContent);
    if (title == null) {
      return null;
    }
    // Conditional update — only sets the title when it is still blank in the DB,
    // ensuring the first message wins even under concurrent requests.
    int updated = chatRepository.setTitleIfBlank(chat.getChatId(), title);
    if (updated > 0) {
      chat.setTitle(title);
      log.debug("Auto-titled chat {}", chat.getChatId());
      return title;
    }
    return null;
  }

  private void logStreamingError(UUID chatId, StreamErrorDetails details, Throwable error) {
    String baseMessage =
        "Streaming error type={} provider={} model={} chatId={} statusCode={} receivedBytes={} providerMessage={}";

    if (isExpectedStreamFailure(details.errorType())) {
      log.warn(
          baseMessage,
          details.errorType(),
          details.provider().name(),
          details.modelId(),
          chatId,
          details.statusCode(),
          details.receivedBytes(),
          extractProviderErrorMessage(error));
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
        extractProviderErrorMessage(error),
        error);
  }

  static String extractProviderErrorMessage(Throwable error) {
    if (error instanceof WebClientResponseException webEx) {
      return formatProviderError(webEx.getResponseBodyAsString(), webEx.getStatusCode().value());
    }
    if (error instanceof RestClientResponseException restEx) {
      return formatProviderError(restEx.getResponseBodyAsString(), restEx.getStatusCode().value());
    }
    return "An error occurred while streaming the response.";
  }

  public static String formatProviderError(String responseBody, int statusCode) {
    if (responseBody != null && !responseBody.isBlank()) {
      String parsed = parseErrorMessage(responseBody);
      if (parsed != null) {
        return parsed;
      }
      return responseBody;
    }
    if (statusCode == 429) {
      return "AI provider rate limit exceeded (HTTP 429). Try again later, switch models/providers, or add credits if required.";
    }
    return "AI provider rejected the request (HTTP %d). Check your model and API key."
        .formatted(statusCode);
  }

  private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

  private static StreamErrorDetails describeStreamError(
      Provider provider, String modelId, long streamedTextUtf8Bytes, Throwable error) {
    Integer statusCode = extractStatusCode(error);
    return new StreamErrorDetails(
        provider,
        modelId,
        statusCode,
        classifyStreamError(error, statusCode),
        streamedTextUtf8Bytes);
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

  private static Integer extractStatusCode(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      switch (current) {
        case WebClientResponseException webEx -> {
          return webEx.getStatusCode().value();
        }
        case RestClientResponseException restEx -> {
          return restEx.getStatusCode().value();
        }
        default -> {
          current = current.getCause();
          depth++;
        }
      }
    }
    return null;
  }

  static String classifyStreamError(Throwable error, Integer statusCode) {
    if (statusCode != null) {
      if (statusCode == 429) {
        return "RATE_LIMIT";
      }
      if (statusCode >= 500) {
        return "SERVER_ERROR";
      }
      if (statusCode >= 400) {
        return "PROVIDER_ERROR";
      }
    }

    if (isTimeoutError(error)) {
      return "TIMEOUT";
    }
    if (isParseError(error)) {
      return "PARSE_ERROR";
    }
    return "UNKNOWN";
  }

  private static boolean isTimeoutError(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      if (current instanceof java.util.concurrent.TimeoutException
          || current instanceof java.net.SocketTimeoutException
          || current instanceof java.net.http.HttpTimeoutException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
        return true;
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  private static boolean isParseError(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
      if (className.contains("parse")
          || className.contains("decode")
          || className.contains("deserial")) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String lowered = message.toLowerCase(Locale.ROOT);
        if (lowered.contains("parse") || lowered.contains("json")) {
          return true;
        }
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  static boolean isExpectedStreamFailure(String errorType) {
    return switch (errorType) {
      case "TIMEOUT", "PARSE_ERROR", "RATE_LIMIT", "SERVER_ERROR", "PROVIDER_ERROR" -> true;
      default -> false;
    };
  }

  static String buildClientStreamErrorMessage(StreamErrorDetails details) {
    String providerName = providerDisplayName(details.provider());
    StringBuilder builder =
        new StringBuilder("Response from ")
            .append(providerName)
            .append(" (")
            .append(details.modelId())
            .append(") was interrupted");

    if (details.statusCode() != null) {
      builder.append(" (HTTP ").append(details.statusCode()).append(")");
    }

    builder.append(". ");
    builder.append(
        switch (details.errorType()) {
          case "TIMEOUT" -> "The provider timed out before completion.";
          case "PARSE_ERROR" -> "The streamed response could not be parsed completely.";
          case "RATE_LIMIT" -> "The provider rate limit was reached.";
          case "SERVER_ERROR" -> "The provider returned a server error.";
          case "PROVIDER_ERROR" -> "The provider rejected the request.";
          default -> "An unexpected streaming error occurred.";
        });
    if (details.receivedBytes() > 0) {
      builder.append(" Partial response shown.");
    }
    return builder.toString();
  }

  private static String providerDisplayName(Provider provider) {
    if (provider == null) {
      return "AI provider";
    }
    return switch (provider) {
      case OPENAI -> "OpenAI";
      case GOOGLE -> "Google";
      case CLAUDE -> "Claude";
      case OPENROUTER -> "OpenRouter";
      case BRAVE_SEARCH -> "Brave Search";
    };
  }

  static String parseErrorMessage(String responseBody) {
    try {
      JsonNode root = ERROR_MAPPER.readTree(responseBody);
      JsonNode errorNode = root.path(EVENT_ERROR);
      if (!errorNode.isMissingNode()) {
        JsonNode messageNode = errorNode.path("message");
        if (messageNode.isTextual() && !messageNode.asText().isBlank()) {
          return messageNode.asText();
        }
      }
      JsonNode messageNode = root.path("message");
      if (messageNode.isTextual() && !messageNode.asText().isBlank()) {
        return messageNode.asText();
      }
    } catch (Exception e) {
      log.debug("Failed to parse provider error response as JSON", e);
    }
    return null;
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

  private List<Message> buildPromptMessages(
      UUID chatId, List<MediaAttachment> mediaAttachments, Long targetUserMessageId,
      String customInstructions, RequestDTO.Provider provider, String modelId) {
    List<MessageEntity> latestMessages =
        new ArrayList<>(
            messageRepository.findByChatIdOrderByCreatedAtDesc(
                chatId, PageRequest.of(0, chatProperties.getContextMessageLimit())));

    List<Message> messages = new ArrayList<>();
    String instructionContent =
        (customInstructions != null && !customInstructions.isBlank())
            ? SYSTEM_PROMPT + "\n\n" + customInstructions
            : SYSTEM_PROMPT;
    if (supportsSystemInstruction(provider, modelId)) {
      messages.add(new SystemMessage(instructionContent));
    } else {
      // Gemma models do not support system instructions - inject as a leading user message instead
      // so that the system prompt and any custom instructions are still applied.
      messages.add(
          new UserMessage(
              "System instructions for this conversation. Follow them when responding.\n\n"
                  + instructionContent));
    }

    List<MessageEntity> ordered = latestMessages.reversed();
    for (MessageEntity entity : ordered) {
      if (!mediaAttachments.isEmpty()
          && targetUserMessageId != null
          && entity.getRole() == Role.USER
          && targetUserMessageId.equals(entity.getId())) {
        messages.add(toUserMessage(entity, mediaAttachments));
        continue;
      }
      messages.add(toChatMessage(entity));
    }
    return messages;
  }

  /**
   * Returns {@code false} for Google Gemma models, which do not support system instructions.
   * All other providers and models support system instructions normally.
   */
  static boolean supportsSystemInstruction(RequestDTO.Provider provider, String modelId) {
    return !(provider == RequestDTO.Provider.GOOGLE
        && modelId != null
        && modelId.startsWith("gemma-"));
  }

  private static String buildAssistantContext(int messageCount, boolean hasSystemInstruction) {
    if (hasSystemInstruction) {
      return "Contains last %d messages including system prompt".formatted(messageCount);
    }
    return "Contains last %d messages (instructions sent as user message)".formatted(messageCount);
  }

  private List<MediaAttachment> validateMediaAttachments(List<MediaAttachment> mediaAttachments) {
    if (mediaAttachments == null || mediaAttachments.isEmpty()) {
      return List.of();
    }

    if (mediaAttachments.size() > chatProperties.getMaxMediaAttachments()) {
      throw new IllegalArgumentException(
          "media supports up to %d attachments".formatted(chatProperties.getMaxMediaAttachments()));
    }

    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      if (attachment == null) {
        throw new IllegalArgumentException("media[%d] must not be null".formatted(i));
      }
    }

    return mediaAttachments;
  }

  private Message toChatMessage(MessageEntity entity) {
    return switch (entity.getRole()) {
      case ASSISTANT -> new AssistantMessage(entity.getMessage());
      case SYSTEM -> new SystemMessage(entity.getMessage());
      default -> new UserMessage(entity.getMessage());
    };
  }

  private UserMessage toUserMessage(MessageEntity entity, List<MediaAttachment> mediaAttachments) {
    List<Media> media = toMediaPayload(mediaAttachments);
    return UserMessage.builder().text(entity.getMessage()).media(media).build();
  }

  private List<Media> toMediaPayload(List<MediaAttachment> mediaAttachments) {
    long totalBytes = 0;
    List<Media> media = new ArrayList<>(mediaAttachments.size());

    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      String normalized = normalizeEncodedData(attachment.data());
      long estimatedDecodedBytes = estimateDecodedBytes(normalized);

      if (estimatedDecodedBytes > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes"
                .formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      totalBytes += estimatedDecodedBytes;
      if (totalBytes > chatProperties.getMaxMediaTotalBytes()) {
        throw new IllegalArgumentException(
            "combined media size exceeds %d bytes".formatted(chatProperties.getMaxMediaTotalBytes()));
      }

      byte[] content;
      try {
        content = Base64.getDecoder().decode(normalized);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("media[%d].data is not valid base64".formatted(i), e);
      }

      if (content.length > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes".formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      media.add(
          new Media(
              MimeTypeUtils.parseMimeType(attachment.mimeType()), new ByteArrayResource(content)));
    }

    return media;
  }

  private String normalizeEncodedData(String encodedData) {
    int commaIndex = encodedData.indexOf(',');
    if (encodedData.startsWith("data:") && commaIndex > 0) {
      return encodedData.substring(commaIndex + 1);
    }
    return encodedData;
  }

  private long estimateDecodedBytes(String normalizedBase64) {
    int length = normalizedBase64.length();
    int padding = 0;
    if (length > 0 && normalizedBase64.charAt(length - 1) == '=') {
      padding++;
      if (length > 1 && normalizedBase64.charAt(length - 2) == '=') {
        padding++;
      }
    }
    return ((long) length * 3 / 4) - padding;
  }

  private MessageEntity persistMessage(UUID chatId, String message, String context) {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    entity.setMessage(message);
    entity.setRole(Role.USER);
    entity.setContext(context);
    entity.setCreatedAt(Instant.now());
    return saveMessageOrThrowIfChatMissing(entity);
  }

  /**
   * Saves a message entity, converting a chat FK constraint violation into a
   * {@link ChatNotFoundException} so callers get a clear "chat not found" error
   * instead of a raw {@code DataIntegrityViolationException}.
   * Non-chat-FK integrity violations are rethrown unchanged.
   */
  private MessageEntity saveMessageOrThrowIfChatMissing(MessageEntity entity) {
    try {
      return messageRepository.save(entity);
    } catch (DataIntegrityViolationException ex) {
      if (isChatFkViolation(ex)) {
        log.warn("FK violation saving message for chat {} — chat no longer exists",
            entity.getChatId(), ex);
        throw new ChatNotFoundException(entity.getChatId());
      }
      throw ex;
    }
  }

  /**
   * Checks whether the given {@link DataIntegrityViolationException} was caused
   * by the {@code messages_chat_id_fkey} foreign-key constraint.
   */
  private static boolean isChatFkViolation(DataIntegrityViolationException ex) {
    Throwable cause = ex.getMostSpecificCause();
    String message = cause.getMessage();
    return message != null && message.contains(CHAT_FK_CONSTRAINT);
  }

  /**
   * Sends an SSE error event indicating the chat was deleted during streaming
   * and completes the emitter. IOExceptions from a disconnected client are
   * logged and swallowed.
   */
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

  private static Long safeLong(Number value) {
    return value == null ? null : value.longValue();
  }

  record StreamErrorDetails(
      Provider provider,
      String modelId,
      Integer statusCode,
      String errorType,
      Long receivedBytes) {}

  public record ClientHolder(
      org.springframework.ai.chat.client.ChatClient client, AutoCloseable closeable)
      implements AutoCloseable {
    @Override
    public void close() {
      if (closeable == null) {
        return;
      }
      try {
        closeable.close();
      } catch (Exception e) {
        log.warn("Failed to close provider client", e);
      }
    }
  }

  private record OffsetPageRequest(int limit, long offset) implements Pageable {

    private static OffsetPageRequest of(int limit, Integer offset) {
      int sanitizedLimit = Math.max(1, limit);
      long sanitizedOffset = offset != null && offset > 0 ? offset.longValue() : 0L;
      return new OffsetPageRequest(sanitizedLimit, sanitizedOffset);
    }

    @Override
    public int getPageNumber() {
      return Math.toIntExact(offset / limit);
    }

    @Override
    public int getPageSize() {
      return limit;
    }

    @Override
    public long getOffset() {
      return offset;
    }

    @Override
    public Sort getSort() {
      return Sort.unsorted();
    }

    @Override
    public Pageable next() {
      return new OffsetPageRequest(limit, offset + limit);
    }

    @Override
    public Pageable previousOrFirst() {
      return hasPrevious() ? new OffsetPageRequest(limit, Math.max(0, offset - limit)) : first();
    }

    @Override
    public Pageable first() {
      return new OffsetPageRequest(limit, 0);
    }

    @Override
    public Pageable withPage(int pageNumber) {
      if (pageNumber < 0) {
        throw new IllegalArgumentException("pageNumber must not be negative");
      }
      return new OffsetPageRequest(limit, (long) pageNumber * limit);
    }

    @Override
    public boolean hasPrevious() {
      return offset > 0;
    }
  }

  /**
   * Resolves a {@link BraveSearchTool} for the given user if they have a Brave Search API key
   * stored.
   */
  private Optional<BraveSearchTool> resolveWebSearchTool(String userId) {
    return apiKeyService
        .getKey(userId, Provider.BRAVE_SEARCH)
        .map(key -> new BraveSearchTool(key, webSearchHttpClient, webSearchProperties));
  }

  boolean supportsTools(RequestDTO.Provider provider, String modelId) {
    if (provider == null || modelId == null || modelId.isBlank()) {
      return true;
    }
    String providerName = provider.name().toLowerCase(Locale.ROOT);
    String normalizedModel = modelId.toLowerCase(Locale.ROOT);
    return chatProperties.getToolDisabledModelPatterns().stream()
        .filter(pattern -> pattern != null && !pattern.isBlank())
        .noneMatch(pattern -> matchesProviderModelPattern(pattern, providerName, normalizedModel));
  }

  private boolean matchesProviderModelPattern(
      String rawPattern, String providerName, String normalizedModel) {
    String pattern = rawPattern.trim().toLowerCase(Locale.ROOT);
    int separator = pattern.indexOf(':');
    if (separator <= 0 || separator == pattern.length() - 1) {
      log.warn("Ignoring invalid app.chat.tool-disabled-model-patterns entry '{}'", rawPattern);
      return false;
    }
    String providerPattern = pattern.substring(0, separator).trim();
    String modelPattern = pattern.substring(separator + 1).trim();
    return wildcardMatches(providerPattern, providerName)
        && wildcardMatches(modelPattern, normalizedModel);
  }

  private boolean wildcardMatches(String pattern, String value) {
    int patternIndex = 0;
    int valueIndex = 0;
    int starIndex = -1;
    int matchIndex = 0;

    while (valueIndex < value.length()) {
      if (patternIndex < pattern.length()
          && (pattern.charAt(patternIndex) == '?' || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
        patternIndex++;
        valueIndex++;
      } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
        starIndex = patternIndex++;
        matchIndex = valueIndex;
      } else if (starIndex != -1) {
        patternIndex = starIndex + 1;
        valueIndex = ++matchIndex;
      } else {
        return false;
      }
    }

    while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
      patternIndex++;
    }
    return patternIndex == pattern.length();
  }

  /**
   * Wires up all available tools for a chat automatically.
   * All tools are always active except brave-search, which still requires a per-user API key
   * and is only wired when webSearchEnabled is true.
   */
  private void wireTools(
      UUID chatId,
      String userId,
      RequestDTO.Provider provider,
      String modelId,
      Boolean webSearchEnabled,
      ToolExecutionCollector collector,
      ChatClient.ChatClientRequestSpec promptSpec) {
    if (!supportsTools(provider, modelId)) {
      log.debug("Skipping tool wiring for provider={} model={} due to app.chat tool gating", provider, modelId);
      return;
    }
    for (com.rft.chat_sribiddi.tools.api.ChatTool tool : toolRegistry.getAll()) {
      if (!isToolEnabledForChat(chatId, tool.getName())) {
        continue;
      }
      switch (tool.getName()) {
        case "current-datetime" ->
            promptSpec.tools(new LoggedCurrentDateTimeTool(currentDateTimeTool, collector));
        case "brave-search" -> {
          // Only wire when the web-search toggle is on; resolveWebSearchTool() will
          // return empty if the user has no Brave Search API key configured.
          if (Boolean.TRUE.equals(webSearchEnabled)) {
            resolveWebSearchTool(userId)
                .map(t -> new LoggedBraveSearchTool(t, collector))
                .ifPresent(promptSpec::tools);
          }
        }
        case "calculator" ->
            promptSpec.tools(new LoggedCalculatorTool(calculatorTool, collector));
        case "url-fetcher" ->
            promptSpec.tools(new LoggedUrlFetcherTool(urlFetcherTool, collector));
        case "python-interpreter" ->
            promptSpec.tools(new LoggedPythonInterpreterTool(pythonInterpreterTool, collector));
        default ->
            // Use the tool's own @Tool-annotated methods directly via Spring AI reflection
            promptSpec.tools(tool);
      }
    }
  }

  private boolean isToolEnabledForChat(UUID chatId, String toolName) {
    return chatToolRepository
        .findByChatIdAndToolName(chatId, toolName)
        .map(ChatToolEntity::isEnabled)
        .orElseGet(
            () ->
                switch (toolName) {
                  case "calculator", "current-datetime", "brave-search" -> true;
                  default -> false;
                });
  }
}
