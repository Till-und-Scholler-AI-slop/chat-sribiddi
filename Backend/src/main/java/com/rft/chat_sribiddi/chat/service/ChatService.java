package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

  private static final String SYSTEM_PROMPT =
      "You are a helpful assistant. Your answer must be structured in markdown.";
  private static final int CONTEXT_MESSAGE_LIMIT = 20;
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ApiKeyService apiKeyService;
  private final ProviderClientFactory providerClientFactory;

  public ChatService(
      ChatRepository chatRepository,
      MessageRepository messageRepository,
      ApiKeyService apiKeyService,
      ProviderClientFactory providerClientFactory) {
    this.chatRepository = chatRepository;
    this.messageRepository = messageRepository;
    this.apiKeyService = apiKeyService;
    this.providerClientFactory = providerClientFactory;
  }

  @Transactional
  public void deleteChat(UUID chatId, String userId) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    messageRepository.deleteByChatId(chat.getChatId());
    chatRepository.deleteById(chat.getChatId());
  }

  @Transactional
  public ChatEntity createChat(ChatEntity chatEntity, String userId) {
    chatEntity.setUserId(userId);
    return chatRepository.save(chatEntity);
  }

  public List<ChatEntity> listChats(String userId) {
    return chatRepository.findByUserIdOrderByCreatedAtDesc(userId);
  }

  public List<MessageEntity> listMessages(UUID chatId, String userId) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));
    return messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);
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

  public ChatResponse sendMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));

    persistMessage(chatId, requestDTO.content(), Role.USER, null);

    List<Message> promptMessages = buildPromptMessages(chatId);
    if (log.isTraceEnabled()) {
      log.trace("Prompt messages: {}", promptMessages);
    }

    String resolvedKey =
        Optional.ofNullable(requestDTO.apiKey())
            .filter(key -> !key.isBlank())
            .or(() -> apiKeyService.getKey(userId, requestDTO.provider()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No API key supplied and no stored key found for provider %s"
                            .formatted(requestDTO.provider())));

    ChatResponse chatResponse;
    try (ClientHolder clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedKey)) {
      var options =
          providerClientFactory.buildOptions(
              requestDTO.provider(), requestDTO.modelId(), resolvedKey);
      var client = clientHolder.client();
      var responseSpec =
          client.prompt().options(options).messages(promptMessages).call();
      chatResponse = responseSpec.chatResponse();
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

    persistMessage(
        chatId,
        assistantResponse,
        Role.ASSISTANT,
        "Contains last %d messages including system prompt".formatted(promptMessages.size()));

    log.debug("Message persisted successfully");

    return chatResponse;
  }

  private List<Message> buildPromptMessages(UUID chatId) {
    List<MessageEntity> latestMessages =
        new ArrayList<>(
            messageRepository.findByChatIdOrderByCreatedAtDesc(
                chatId, PageRequest.of(0, CONTEXT_MESSAGE_LIMIT)));

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(SYSTEM_PROMPT));
    latestMessages.reversed().stream().map(this::toChatMessage).forEach(messages::add);
    return messages;
  }

  private Message toChatMessage(MessageEntity entity) {
    return switch (entity.getRole()) {
      case ASSISTANT -> new AssistantMessage(entity.getMessage());
      case SYSTEM -> new SystemMessage(entity.getMessage());
      default -> new UserMessage(entity.getMessage());
    };
  }

  private void persistMessage(UUID chatId, String message, Role role, String context) {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    entity.setMessage(message);
    entity.setRole(role);
    entity.setContext(context);
    entity.setCreatedAt(Instant.now());
    messageRepository.save(entity);
  }

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
}
