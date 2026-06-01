package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.ToolCallLogResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

  private final MessageRepository messageRepository;
  private final ChatRepository chatRepository;
  private final ToolCallLogRepository toolCallLogRepository;
  private final ChatProperties chatProperties;

  public Optional<MessageEntity> findByIdAndChatId(Long messageId, UUID chatId) {
    return messageRepository.findByIdAndChatId(messageId, chatId);
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
  public void deleteMessagesByChatId(UUID chatId) {
    messageRepository.deleteByChatId(chatId);
  }

  @Transactional
  public void deleteMessagesAfter(UUID chatId, Long messageId) {
    messageRepository.deleteByIdGreaterThanAndChatId(chatId, messageId);
  }

  @Transactional
  public void updateMessage(MessageEntity message) {
    messageRepository.save(message);
  }

  public MessageEntity persistUserMessage(UUID chatId, String message, String context) {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    entity.setMessage(message);
    entity.setRole(Role.USER);
    entity.setContext(context);
    entity.setCreatedAt(Instant.now());
    return saveMessageOrThrowIfChatMissing(entity);
  }

  public MessageEntity persistAssistantMessage(
      UUID chatId,
      String message,
      String context,
      long promptTokens,
      long generationTokens,
      long totalTokens) {
    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setChatId(chatId);
    assistantMessage.setMessage(message);
    assistantMessage.setRole(Role.ASSISTANT);
    assistantMessage.setContext(context);
    assistantMessage.setCreatedAt(Instant.now());
    assistantMessage.setPromptTokens(promptTokens);
    assistantMessage.setGenerationTokens(generationTokens);
    assistantMessage.setTotalTokens(totalTokens);
    return saveMessageOrThrowIfChatMissing(assistantMessage);
  }

  /**
   * Saves a message entity, converting a chat FK constraint violation into a
   * {@link ChatNotFoundException} so callers get a clear "chat not found" error
   * instead of a raw {@code DataIntegrityViolationException}.
   * Non-chat-FK integrity violations are rethrown unchanged.
   */
  public MessageEntity saveMessageOrThrowIfChatMissing(MessageEntity entity) {
    try {
      return messageRepository.save(entity);
    } catch (DataIntegrityViolationException ex) {
      if (DbConstraintChecker.isChatFkViolation(ex)) {
        log.warn("FK violation saving message for chat {} — chat no longer exists",
            entity.getChatId(), ex);
        throw new ChatNotFoundException(entity.getChatId());
      }
      throw ex;
    }
  }

  /**
   * Trims whitespace from the title and truncates it to {@code maxChatTitleLength}.
   * Returns {@code null} if the result would be blank.
   */
  public String normalizeChatTitle(String title) {
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
  @Transactional
  public String autoTitleChatIfNeeded(ChatEntity chat, String messageContent) {
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
}
