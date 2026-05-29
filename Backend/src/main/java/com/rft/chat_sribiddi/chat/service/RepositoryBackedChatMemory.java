package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;

final class RepositoryBackedChatMemory implements ChatMemoryRepository {

  private static final String PREFIX = "chat-sribiddi";
  private static final String SOURCE_MESSAGE_ID_METADATA = "sourceMessageId";

  private final MessageRepository messageRepository;

  RepositoryBackedChatMemory(MessageRepository messageRepository) {
    this.messageRepository = messageRepository;
  }

  static ChatMemory chatMemory(MessageRepository messageRepository, int maxMessages) {
    // The chat / before-message scoping is carried via the conversationId (see conversationId(..)
    // and findByConversationId(..)), not bound to this instance.
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(new RepositoryBackedChatMemory(messageRepository))
        .maxMessages(Math.max(1, maxMessages))
        .build();
  }

  static String conversationId(UUID chatId, Long beforeMessageId, int maxMessages) {
    return "%s:%s:%s:%d"
        .formatted(
            PREFIX,
            chatId,
            beforeMessageId == null ? "none" : beforeMessageId,
            Math.max(1, maxMessages));
  }

  @Override
  public List<String> findConversationIds() {
    return List.of();
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    ConversationWindow window = ConversationWindow.parse(conversationId);
    if (window == null) {
      return List.of();
    }
    List<MessageEntity> descending =
        messageRepository.findContextMessagesBefore(
            window.chatId(),
            window.beforeMessageId(),
            PageRequest.of(0, window.maxMessages()));
    List<MessageEntity> ascending = new ArrayList<>(descending);
    ascending = ascending.reversed();
    return ascending.stream().map(RepositoryBackedChatMemory::toChatMessage).toList();
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    // The application persists user and assistant messages itself. Spring AI's
    // MessageWindowChatMemory calls saveAll(...); treating writes as no-op avoids
    // duplicate DB rows while still letting the advisor read through this repository.
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    // Chat deletion is handled by ChatService and MessageRepository.
  }

  private static Message toChatMessage(MessageEntity entity) {
    return switch (entity.getRole()) {
      case ASSISTANT ->
          AssistantMessage.builder()
              .content(entity.getMessage())
              .properties(sourceMessageMetadata(entity.getId()))
              .build();
      case SYSTEM ->
          SystemMessage.builder()
              .text(entity.getMessage())
              .metadata(sourceMessageMetadata(entity.getId()))
              .build();
      case USER ->
          UserMessage.builder()
              .text(entity.getMessage())
              .metadata(sourceMessageMetadata(entity.getId()))
              .build();
    };
  }

  private static Map<String, Object> sourceMessageMetadata(Long sourceMessageId) {
    return sourceMessageId == null ? Map.of() : Map.of(SOURCE_MESSAGE_ID_METADATA, sourceMessageId);
  }

  private record ConversationWindow(UUID chatId, Long beforeMessageId, int maxMessages) {

    private static ConversationWindow parse(String conversationId) {
      if (conversationId == null || conversationId.isBlank()) {
        return null;
      }
      String[] parts = conversationId.split(":");
      if (parts.length != 4 || !PREFIX.equals(parts[0])) {
        return null;
      }
      try {
        UUID chatId = UUID.fromString(parts[1]);
        Long beforeMessageId = "none".equals(parts[2]) ? null : Long.valueOf(parts[2]);
        int maxMessages = Math.max(1, Integer.parseInt(parts[3]));
        return new ConversationWindow(chatId, beforeMessageId, maxMessages);
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }
}
