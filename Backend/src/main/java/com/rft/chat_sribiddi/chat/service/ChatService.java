package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.ImportChatsRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.ToolCallLogResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import com.rft.chat_sribiddi.chat.service.ChatStreamSubscriber.ChatStreamSubscription;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageRequest;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.ChatUsage;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.chat.service.MediaValidationService.ValidatedMediaAttachment;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.ManagedQuotaReservation;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private static final String ATTACHMENTS_PREFIX = "attachments:";

  private static final String CHAT_FK_CONSTRAINT = "messages_chat_id_fkey";

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ToolCallLogRepository toolCallLogRepository;
  private final ChatToolRepository chatToolRepository;
  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;
  private final MediaValidationService mediaValidationService;
  private final DocumentRetrievalService documentRetrievalService;
  private final UserSettingsService userSettingsService;
  private final PromptToolBinder promptToolBinder;
  private final PromptMessageBuilder promptMessageBuilder;
  private final ChatUsageRecorder chatUsageRecorder;
  private final ToolCallLogRecorder toolCallLogRecorder;
  private final ChatStreamSubscriber chatStreamSubscriber;
  private final BillingModeResolver billingModeResolver;
  private final EntitlementService entitlementService;
  private final StreamConcurrencyGate streamConcurrencyGate;

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

  @Transactional
  public List<ChatEntity> importChats(String userId, ImportChatsRequestDTO request) {
    if (request == null || request.chats() == null || request.chats().isEmpty()) {
      return List.of();
    }

    return request.chats().stream()
        .filter(Objects::nonNull)
        .map(chat -> importChat(userId, chat))
        .toList();
  }

  private ChatEntity importChat(String userId, ImportChatsRequestDTO.ImportedChat importedChat) {
    ChatEntity chat = new ChatEntity();
    chat.setUserId(userId);
    chat.setTitle(normalizeChatTitle(importedChat.title()));
    chat.setCreatedAt(importedChat.createdAt());
    ChatEntity savedChat = chatRepository.save(chat);

    List<ImportChatsRequestDTO.ImportedMessage> messages =
        importedChat.messages() == null ? List.of() : importedChat.messages();
    for (ImportChatsRequestDTO.ImportedMessage importedMessage : messages) {
      if (importedMessage == null) {
        continue;
      }
      MessageEntity entity = new MessageEntity();
      entity.setChatId(savedChat.getChatId());
      entity.setMessage(importedMessage.message());
      entity.setRole(importRole(importedMessage.role()));
      entity.setCreatedAt(importedMessage.createdAt());
      saveMessageOrThrowIfChatMissing(entity);
    }
    return savedChat;
  }

  private Role importRole(String role) {
    String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "USER" -> Role.USER;
      case "ASSISTANT" -> Role.ASSISTANT;
      case "SYSTEM" -> Role.SYSTEM;
      default -> throw new IllegalArgumentException("Unsupported import role: " + role);
    };
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

    ResolvedCall resolvedCall = resolveCall(userId, requestDTO);
    MessagePreparation prep =
        prepareUserMessage(chatId, chat, userId, requestDTO, resolvedCall.entitlements());
    if (log.isTraceEnabled()) {
      log.trace("Prompt messages: {}", prep.promptBatch().combinedMessages());
    }

    Plan plan = chatUsageRecorder.resolvePlan(userId);

    ChatResponseExtractor.ChatCallResult completion;
    ToolExecutionCollector toolExecutionCollector;
    boolean providerCallSucceeded = false;
    try (ManagedQuotaReservation reservation = resolvedCall.quotaReservation()) {
      try (ProviderClientHandle clientHolder =
          providerClientFactory.getClient(
              requestDTO.provider(), resolvedCall.apiKey(), resolvedCall.billingMode())) {
        PreparedPrompt preparedPrompt =
            preparePromptSpec(
                chatId, userId, requestDTO, prep.promptBatch(), resolvedCall.apiKey(), clientHolder);
        toolExecutionCollector = preparedPrompt.toolExecutionCollector();
        completion = ChatResponseExtractor.call(preparedPrompt.promptSpec());
        providerCallSucceeded = true;
        reservation.commit();
      } catch (RuntimeException | Error error) {
        chatUsageRecorder.recordRequestMetric(
            requestDTO.provider(), requestDTO.modelId(), plan, false);
        throw error;
      }
    }

    if (log.isTraceEnabled()) {
      log.trace("Chat response: {}", completion.response());
    }

    String assistantResponse = Optional.ofNullable(completion.content()).orElse("");

    TokenUsage tokenUsage =
        chatUsageRecorder.tokenUsage(
            ChatResponseExtractor.usage(completion.response()).orElse(null));

    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setChatId(chatId);
    assistantMessage.setMessage(assistantResponse);
    assistantMessage.setRole(Role.ASSISTANT);
    assistantMessage.setContext(
        promptMessageBuilder.assistantContext(
            prep.promptBatch().totalMessageCount(), requestDTO.provider(), requestDTO.modelId()));
    assistantMessage.setCreatedAt(Instant.now());
    assistantMessage.setPromptTokens(tokenUsage.promptTokens());
    assistantMessage.setGenerationTokens(tokenUsage.generationTokens());
    assistantMessage.setTotalTokens(tokenUsage.totalTokens());
    saveMessageOrThrowIfChatMissing(assistantMessage);
    toolCallLogRecorder.recordAssistantToolCalls(
        assistantMessage, toolExecutionCollector, assistantResponse);
    chatUsageRecorder.recordUsage(
        new ChatUsage(
            userId,
            requestDTO.provider(),
            requestDTO.modelId(),
            tokenUsage,
            assistantMessage.getId(),
            resolvedCall.billingMode(),
            plan,
            providerCallSucceeded));

    log.debug("Message persisted successfully");

    return new SendMessageResponseDTO(
        assistantResponse,
        tokenUsage.promptTokens(),
        tokenUsage.generationTokens(),
        tokenUsage.totalTokens(),
        prep.updatedTitle());
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

    ResolvedCall resolvedCall = resolveCall(userId, requestDTO);
    Plan plan = chatUsageRecorder.resolvePlan(userId);
    MessagePreparation prep =
        prepareUserMessage(chatId, chat, userId, requestDTO, resolvedCall.entitlements());

    streamConcurrencyGate.acquire(userId);
    try {
      return createAndSubscribeStream(
          userId,
          requestDTO,
          prep.promptBatch(),
          chatId,
          prep.updatedTitle(),
          resolvedCall,
          plan);
    } catch (RuntimeException | Error error) {
      streamConcurrencyGate.release(userId);
      throw error;
    }
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

    // Run entitlement checks before mutating chat history so that a rejected request
    // never leaves the database in a half-edited state.
    ResolvedCall resolvedCall = resolveCall(userId, requestDTO);
    Plan plan = chatUsageRecorder.resolvePlan(userId);
    List<ValidatedMediaAttachment> validatedMedia =
        mediaValidationService.validateAndConvertAttachments(
            requestDTO.media(), resolvedCall.entitlements());
    String userContext = buildMediaContext(validatedMedia);

    userMessage.setMessage(requestDTO.content());
    userMessage.setContext(userContext);
    messageRepository.save(userMessage);

    messageRepository.deleteByIdGreaterThanAndChatId(chatId, messageId);

    String customInstructions =
        userSettingsService.getCustomInstructions(userId).orElse(null);
    PromptMessageBatch promptBatch =
        promptMessageBuilder.buildBatch(
            new PromptMessageRequest(
                chatId,
                mediaValidationService.mediaPayload(validatedMedia),
                messageId,
                customInstructions,
                requestDTO.provider(),
                requestDTO.modelId(),
                resolvedCall.entitlements(),
                documentRetrievalService.retrieveDocuments(chatId, userId, requestDTO.content())));

    streamConcurrencyGate.acquire(userId);
    try {
      return createAndSubscribeStream(
          userId, requestDTO, promptBatch, chatId, null, resolvedCall, plan);
    } catch (RuntimeException | Error error) {
      streamConcurrencyGate.release(userId);
      throw error;
    }
  }

  private record MessagePreparation(
      String updatedTitle,
      PromptMessageBatch promptBatch) {}

  private record PreparedPrompt(
      ChatClient.ChatClientRequestSpec promptSpec, ToolExecutionCollector toolExecutionCollector) {}

  private String buildMediaContext(List<ValidatedMediaAttachment> validatedMedia) {
    return validatedMedia.isEmpty()
        ? null
        : ATTACHMENTS_PREFIX
            + validatedMedia.stream()
                .map(ValidatedMediaAttachment::mimeType)
                .collect(Collectors.joining(","));
  }

  private MessagePreparation prepareUserMessage(
      UUID chatId,
      ChatEntity chat,
      String userId,
      RequestDTO requestDTO,
      Entitlements entitlements) {
    List<ValidatedMediaAttachment> validatedMedia =
        mediaValidationService.validateAndConvertAttachments(requestDTO.media(), entitlements);
    String userContext = buildMediaContext(validatedMedia);
    MessageEntity persistedUserMessage = persistMessage(chatId, requestDTO.content(), userContext);
    String updatedTitle = autoTitleChatIfNeeded(chat, requestDTO.content());
    String customInstructions = userSettingsService.getCustomInstructions(userId).orElse(null);
    PromptMessageBatch promptBatch =
        promptMessageBuilder.buildBatch(
            new PromptMessageRequest(
                chatId,
                mediaValidationService.mediaPayload(validatedMedia),
                persistedUserMessage.getId(),
                customInstructions,
                requestDTO.provider(),
                requestDTO.modelId(),
                entitlements,
                documentRetrievalService.retrieveDocuments(chatId, userId, requestDTO.content())));
    return new MessagePreparation(updatedTitle, promptBatch);
  }

  private SseEmitter createAndSubscribeStream(
      String userId, RequestDTO requestDTO, PromptMessageBatch promptBatch,
      UUID chatId, String updatedTitle, ResolvedCall resolvedCall, Plan plan) {
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    ProviderClientHandle clientHolder =
        providerClientFactory.getClient(
            requestDTO.provider(), resolvedCall.apiKey(), resolvedCall.billingMode());
    try {
      PreparedPrompt preparedPrompt =
          preparePromptSpec(
              chatId, userId, requestDTO, promptBatch, resolvedCall.apiKey(), clientHolder);
      String assistantContext =
          promptMessageBuilder.assistantContext(
              promptBatch.totalMessageCount(), requestDTO.provider(), requestDTO.modelId());
      chatStreamSubscriber.subscribe(
          new ChatStreamSubscription(
              preparedPrompt.promptSpec(),
              emitter,
              clientHolder,
              userId,
              requestDTO.provider(),
              requestDTO.modelId(),
              resolvedCall.billingMode(),
              plan,
              chatId,
              assistantContext,
              updatedTitle,
              preparedPrompt.toolExecutionCollector(),
              resolvedCall.quotaReservation()));
    } catch (RuntimeException | Error error) {
      clientHolder.close();
      throw error;
    }
    emitter.onCompletion(() -> streamConcurrencyGate.release(userId));
    emitter.onError(_ -> streamConcurrencyGate.release(userId));
    emitter.onTimeout(() -> streamConcurrencyGate.release(userId));
    return emitter;
  }

  private org.springframework.ai.chat.prompt.ChatOptions buildOptions(
      RequestDTO requestDTO, String apiKey) {
    if (requestDTO.options() == null) {
      return providerClientFactory.buildOptions(requestDTO.provider(), requestDTO.modelId(), apiKey);
    }
    return providerClientFactory.buildOptions(
        requestDTO.provider(), requestDTO.modelId(), apiKey, requestDTO.options());
  }

  private PreparedPrompt preparePromptSpec(
      UUID chatId,
      String userId,
      RequestDTO requestDTO,
      PromptMessageBatch promptBatch,
      String apiKey,
      ProviderClientHandle clientHolder) {
    var options = buildOptions(requestDTO, apiKey);
    var promptSpec =
        clientHolder.client().prompt().options(options.mutate()).messages(promptBatch.requestMessages());
    PromptAdvisors.bind(promptSpec, promptBatch);
    ToolExecutionCollector toolExecutionCollector = new ToolExecutionCollector();
    promptToolBinder.bindTools(
        chatId,
        userId,
        requestDTO.provider(),
        requestDTO.modelId(),
        requestDTO.webSearchEnabled(),
        requestDTO.toolConsents(),
        toolExecutionCollector,
        promptSpec);
    return new PreparedPrompt(promptSpec, toolExecutionCollector);
  }

  private ResolvedCall resolveCall(String userId, RequestDTO requestDTO) {
    BillingMode requestedMode = entitlementService.resolveBillingMode(userId);
    BillingModeResolver.ResolvedKey resolvedKey =
        billingModeResolver.resolve(
            userId, requestDTO.provider(), requestDTO.modelId(), requestedMode);
    ManagedQuotaReservation reservation = ManagedQuotaReservation.noop();
    if (resolvedKey.effectiveMode() == BillingMode.MANAGED) {
      reservation = entitlementService.reserveManagedCall(userId);
    }
    return new ResolvedCall(
        resolvedKey.apiKey(),
        resolvedKey.effectiveMode(),
        entitlementService.get(userId),
        reservation);
  }

  private record ResolvedCall(
      String apiKey,
      BillingMode billingMode,
      Entitlements entitlements,
      ManagedQuotaReservation quotaReservation) {}

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

}
