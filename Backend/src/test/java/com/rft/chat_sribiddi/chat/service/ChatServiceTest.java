package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ApiKeyService apiKeyService;
  @Mock private ProviderClientFactory providerClientFactory;

  @Mock private ChatClient chatClient;
  @Mock private ChatClient.ChatClientRequestSpec requestSpec;
  @Mock private ChatClient.CallResponseSpec callResponseSpec;

  private ChatService chatService;
  private UUID chatId;
  private String userId;

  @BeforeEach
  void setUp() {
    ChatProperties chatProperties = new ChatProperties();
    chatService =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory, chatProperties);
    chatId = UUID.randomUUID();
    userId = "user-123";

    AtomicLong ids = new AtomicLong(100);
    lenient().when(messageRepository.save(any(MessageEntity.class)))
        .thenAnswer(
            invocation -> {
              MessageEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                entity.setId(ids.getAndIncrement());
              }
              return entity;
            });
  }

  @Test
  void sendMessage_resolvesStoredKey_andPersistsMessages() {
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", null, null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", "stored-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder()
                .model("gpt-5.2-2025-12-11")
                .build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))));
    when(callResponseSpec.chatResponse()).thenReturn(response);

    ChatResponse result = chatService.sendMessage(chatId, userId, request);

    assertThat(result).isSameAs(response);

    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    assertThat(savedMessages.getAllValues())
        .extracting(MessageEntity::getRole)
        .containsExactly(Role.USER, Role.ASSISTANT);
  }

  @Test
  void sendMessage_withMedia_addsMultimodalUserMessage() {
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("png".getBytes()))));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity latestUserMessage = new MessageEntity();
    latestUserMessage.setId(100L);
    latestUserMessage.setRole(Role.USER);
    latestUserMessage.setMessage("describe image");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(latestUserMessage));

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.CLAUDE, "claude-sonnet-4-5", "claude-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("claude-sonnet-4-5").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    Message lastMessage = messagesCaptor.getValue().getLast();
    assertThat(lastMessage).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) lastMessage).getMedia()).hasSize(1);
  }

  @Test
  void sendMessage_withConcurrentLatestUserMessage_attachesMediaToPersistedMessageId() {
    RequestDTO request =
        new RequestDTO(
            "my image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("abc".getBytes()))));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity concurrentNewerUserMessage = new MessageEntity();
    concurrentNewerUserMessage.setId(200L);
    concurrentNewerUserMessage.setRole(Role.USER);
    concurrentNewerUserMessage.setMessage("other request text");

    MessageEntity persistedCurrentUserMessage = new MessageEntity();
    persistedCurrentUserMessage.setId(100L);
    persistedCurrentUserMessage.setRole(Role.USER);
    persistedCurrentUserMessage.setMessage("my image");

    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(concurrentNewerUserMessage, persistedCurrentUserMessage));

    when(providerClientFactory.getClient(RequestDTO.Provider.CLAUDE, "claude-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.CLAUDE, "claude-sonnet-4-5", "claude-key"))
        .thenReturn(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("claude-sonnet-4-5").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));

    chatService.sendMessage(chatId, userId, request);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());

    List<Message> prompt = messagesCaptor.getValue();
    UserMessage firstUser = (UserMessage) prompt.get(1);
    UserMessage secondUser = (UserMessage) prompt.get(2);

    assertThat(firstUser.getText()).isEqualTo("my image");
    assertThat(firstUser.getMedia()).hasSize(1);
    assertThat(secondUser.getText()).isEqualTo("other request text");
    assertThat(secondUser.getMedia()).isEmpty();
  }

  @Test
  void sendMessage_withNullMediaEntry_throwsBadRequest_andSkipsPersistence() {
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            java.util.Arrays.asList((RequestDTO.MediaAttachment) null));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("media[0] must not be null");

    verify(messageRepository, never()).save(any());
    verify(providerClientFactory, never()).getClient(any(), anyString());
  }

  @Test
  void sendMessage_withOversizedMedia_throwsBadRequest_andSkipsProviderCall() {
    String oversized = Base64.getEncoder().encodeToString(new byte[6 * 1024 * 1024]);
    RequestDTO request =
        new RequestDTO(
            "describe image",
            RequestDTO.Provider.CLAUDE,
            "claude-sonnet-4-5",
            "claude-key",
            List.of(new RequestDTO.MediaAttachment("image/png", oversized)));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity latestUserMessage = new MessageEntity();
    latestUserMessage.setId(100L);
    latestUserMessage.setRole(Role.USER);
    latestUserMessage.setMessage("describe image");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any()))
        .thenReturn(List.of(latestUserMessage));

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds");

    verify(providerClientFactory, never()).getClient(any(), anyString());
  }

  @Test
  void sendMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request = new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key", null);

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }

  @Test
  void sendMessage_respectsCustomMaxMediaAttachments() {
    ChatProperties customProperties = new ChatProperties();
    customProperties.setMaxMediaAttachments(1);
    ChatService customService =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory, customProperties);

    RequestDTO request =
        new RequestDTO(
            "two images",
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            "key",
            List.of(
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("a".getBytes())),
                new RequestDTO.MediaAttachment(
                    "image/png", Base64.getEncoder().encodeToString("b".getBytes()))));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    assertThatThrownBy(() -> customService.sendMessage(chatId, userId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("media supports up to 1 attachments");
  }
}
