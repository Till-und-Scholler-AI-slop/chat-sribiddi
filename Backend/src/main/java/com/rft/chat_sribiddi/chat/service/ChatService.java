package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

  private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
  private static final int CONTEXT_MESSAGE_LIMIT = 20;
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;

  @Qualifier("openAiChatClient")
  private final ChatClient openAiChatClient;

  @Qualifier("googleAiChatClient")
  private final ChatClient googleAiChatClient;

  public ChatService(
      ChatRepository chatRepository,
      MessageRepository messageRepository,
      ChatClient openAiChatClient,
      ChatClient googleAiChatClient) {
    this.chatRepository = chatRepository;
    this.messageRepository = messageRepository;
    this.openAiChatClient = openAiChatClient;
    this.googleAiChatClient = googleAiChatClient;
  }

  public ChatEntity createChat(ChatEntity chatEntity) {
    return chatRepository.save(chatEntity);
  }

  public ChatResponse sendMessage(UUID chatId, RequestDTO requestDTO) {
    chatRepository
        .findById(chatId)
        .orElseThrow(() -> new IllegalArgumentException("Chat not found: " + chatId));

    persistMessage(chatId, requestDTO.content(), Role.USER, null);

    List<Message> promptMessages = buildPromptMessages(chatId);
    log.info("Prompt messages: {}", promptMessages);

    ChatClient client = selectClient(requestDTO.provider());

    ChatClient.CallResponseSpec responseSpec =
        client
            .prompt()
            .options(ChatOptions.builder().model(requestDTO.modelId()).build())
            .messages(promptMessages)
            .call();
    ChatResponse chatResponse = responseSpec.chatResponse();

    log.info("Chat response: {}", chatResponse);

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

    log.info("Message persisted successfully");

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

  private ChatClient selectClient(Provider provider) {
    return switch (provider) {
      case OPENAI -> openAiChatClient;
      case GOOGLE -> googleAiChatClient;
    };
  }
}
