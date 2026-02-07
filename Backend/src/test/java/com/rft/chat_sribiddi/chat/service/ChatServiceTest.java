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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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
    chatService =
        new ChatService(chatRepository, messageRepository, apiKeyService, providerClientFactory);
    chatId = UUID.randomUUID();
    userId = "user-123";
  }

  @Test
  void sendMessage_resolvesStoredKey_andPersistsMessages() {
    // arrange
    RequestDTO request =
        new RequestDTO("hi", RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", null);

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    when(apiKeyService.getKey(userId, RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of("stored-key"));

    // stub chat client chain
    when(providerClientFactory.getClient(RequestDTO.Provider.OPENAI, "stored-key"))
        .thenReturn(new ChatService.ClientHolder(chatClient, null));
    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2-2025-12-11", "stored-key"))
        .thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("gpt-5.2-2025-12-11").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);

    ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))));
    when(callResponseSpec.chatResponse()).thenReturn(response);

    // act
    ChatResponse result = chatService.sendMessage(chatId, userId, request);

    // assert
    assertThat(result).isSameAs(response);

    ArgumentCaptor<MessageEntity> savedMessages = ArgumentCaptor.forClass(MessageEntity.class);
    verify(messageRepository, times(2)).save(savedMessages.capture());
    assertThat(savedMessages.getAllValues())
        .extracting(MessageEntity::getRole)
        .containsExactly(Role.USER, Role.ASSISTANT);
  }

  @Test
  void sendMessage_missingChat_throwsNotFound() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    RequestDTO request = new RequestDTO("hi", RequestDTO.Provider.GOOGLE, "gemini", "key");

    assertThatThrownBy(() -> chatService.sendMessage(chatId, userId, request))
        .isInstanceOf(ChatNotFoundException.class);
  }
}
