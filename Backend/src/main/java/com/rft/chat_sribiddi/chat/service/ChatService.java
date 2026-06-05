package com.rft.chat_sribiddi.chat.service;

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
import com.rft.chat_sribiddi.chat.service.ChatStreamSubscriber.ChatStreamSubscription;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.ChatUsage;
import com.rft.chat_sribiddi.chat.service.ChatUsageRecorder.TokenUsage;
import com.rft.chat_sribiddi.chat.service.MediaValidationService.ValidatedMediaAttachment;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatRepository chatRepository;
  private final ChatToolRepository chatToolRepository;
  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;
  private final ChatMessageService chatMessageService;
  private final ChatRequestResolver chatRequestResolver;
  private final ChatUsageRecorder chatUsageRecorder;
  private final ToolCallLogRecorder toolCallLogRecorder;
  private final ChatStreamSubscriber chatStreamSubscriber;
  private final StreamConcurrencyGate streamConcurrencyGate;

  @Transactional
  public void deleteChat(UUID chatId, String userId) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    chatMessageService.deleteMessagesByChatId(chat.getChatId());
    chatToolRepository.deleteByChatId(chat.getChatId());
    chatRepository.deleteById(chat.getChatId());
  }

  @Transactional
  public ChatEntity createChat(ChatEntity chatEntity, String userId) {
    chatEntity.setUserId(userId);
    chatEntity.setTitle(chatMessageService.normalizeChatTitle(chatEntity.getTitle()));
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
    chat.setTitle(chatMessageService.normalizeChatTitle(importedChat.title()));
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
      chatMessageService.saveMessageOrThrowIfChatMissing(entity);
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
    return chatMessageService.listMessages(chatId, userId, limit, null);
  }

  public List<MessageEntity> listMessages(UUID chatId, String userId, Integer limit, Integer offset) {
    return chatMessageService.listMessages(chatId, userId, limit, offset);
  }

  public List<MessageResponseDTO> listMessageResponses(UUID chatId, String userId, Integer limit) {
    return chatMessageService.listMessageResponses(chatId, userId, limit, null);
  }

  public List<MessageResponseDTO> listMessageResponses(
      UUID chatId, String userId, Integer limit, Integer offset) {
    return chatMessageService.listMessageResponses(chatId, userId, limit, offset);
  }

  public List<ToolCallLogResponseDTO> listToolCallLogs(Long messageId, String userId) {
    return chatMessageService.listToolCallLogs(messageId, userId);
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

    ResolvedCall resolvedCall = chatRequestResolver.resolveCall(userId, requestDTO);
    MessagePreparation prep =
        chatRequestResolver.prepareUserMessage(
            chatId, chat, userId, requestDTO, resolvedCall.accessLimits());
    if (log.isTraceEnabled()) {
      log.trace("Prompt messages: {}", prep.promptBatch().combinedMessages());
    }

    ChatResponseExtractor.ChatCallResult completion;
    ToolExecutionCollector toolExecutionCollector;
    boolean providerCallSucceeded = false;
    try (var reservation = resolvedCall.quotaReservation()) {
      try (ProviderClientHandle clientHolder =
          providerClientFactory.getClient(requestDTO.provider(), resolvedCall.credential())) {
        PreparedPrompt preparedPrompt =
            chatRequestResolver.preparePromptSpec(
                chatId, userId, requestDTO, prep.promptBatch(), resolvedCall.apiKey(), clientHolder);
        toolExecutionCollector = preparedPrompt.toolExecutionCollector();
        completion = ChatResponseExtractor.call(preparedPrompt.promptSpec());
        providerCallSucceeded = true;
        reservation.commit();
      } catch (RuntimeException | Error error) {
        chatUsageRecorder.recordRequestMetric(
            requestDTO.provider(), requestDTO.modelId(), false);
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

    MessageEntity assistantMessage =
        chatMessageService.persistAssistantMessage(
            chatId,
            assistantResponse,
            chatRequestResolver.buildAssistantContext(
                prep.promptBatch().totalMessageCount(), requestDTO.provider(), requestDTO.modelId()),
            tokenUsage.promptTokens(),
            tokenUsage.generationTokens(),
            tokenUsage.totalTokens());

    toolCallLogRecorder.recordAssistantToolCalls(
        assistantMessage, toolExecutionCollector, assistantResponse);
    chatUsageRecorder.recordUsage(
        new ChatUsage(
            userId,
            requestDTO.provider(),
            requestDTO.modelId(),
            tokenUsage,
            assistantMessage.getId(),
            resolvedCall.credentialSource(),
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

    ResolvedCall resolvedCall = chatRequestResolver.resolveCall(userId, requestDTO);
    MessagePreparation prep =
        chatRequestResolver.prepareUserMessage(
            chatId, chat, userId, requestDTO, resolvedCall.accessLimits());

    streamConcurrencyGate.acquire(userId);
    try {
      return createAndSubscribeStream(
          userId,
          requestDTO,
          prep.promptBatch(),
          chatId,
          prep.updatedTitle(),
          resolvedCall);
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
        chatMessageService.findByIdAndChatId(messageId, chatId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Message %d not found in chat %s".formatted(messageId, chatId)));

    if (userMessage.getRole() != Role.USER) {
      throw new IllegalArgumentException("Only user messages can be edited.");
    }

    // Run entitlement checks before mutating chat history so that a rejected request
    // never leaves the database in a half-edited state.
    ResolvedCall resolvedCall = chatRequestResolver.resolveCall(userId, requestDTO);
    List<ValidatedMediaAttachment> validatedMedia =
        chatRequestResolver.validateMedia(requestDTO.media(), resolvedCall.accessLimits());
    String userContext = chatRequestResolver.buildMediaContext(validatedMedia);

    userMessage.setMessage(requestDTO.content());
    userMessage.setContext(userContext);
    chatMessageService.updateMessage(userMessage);

    chatMessageService.deleteMessagesAfter(chatId, messageId);

    var promptBatch =
        chatRequestResolver.buildPromptBatchForEdit(
            chatId, userId, requestDTO, resolvedCall.accessLimits(), messageId, validatedMedia);

    streamConcurrencyGate.acquire(userId);
    try {
      return createAndSubscribeStream(
          userId, requestDTO, promptBatch, chatId, null, resolvedCall);
    } catch (RuntimeException | Error error) {
      streamConcurrencyGate.release(userId);
      throw error;
    }
  }

  private SseEmitter createAndSubscribeStream(
      String userId, RequestDTO requestDTO, PromptMessageBuilder.PromptMessageBatch promptBatch,
      UUID chatId, String updatedTitle, ResolvedCall resolvedCall) {
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    ProviderClientHandle clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedCall.credential());
    try {
      PreparedPrompt preparedPrompt =
          chatRequestResolver.preparePromptSpec(
              chatId, userId, requestDTO, promptBatch, resolvedCall.apiKey(), clientHolder);
      String assistantContext =
          chatRequestResolver.buildAssistantContext(
              promptBatch.totalMessageCount(), requestDTO.provider(), requestDTO.modelId());
      chatStreamSubscriber.subscribe(
          new ChatStreamSubscription(
              preparedPrompt.promptSpec(),
              emitter,
              clientHolder,
              userId,
              requestDTO.provider(),
              requestDTO.modelId(),
              resolvedCall.credentialSource(),
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
}
