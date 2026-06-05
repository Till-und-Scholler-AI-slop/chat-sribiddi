package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.access.AccessRestrictionException;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private ChatToolRepository chatToolRepository;
  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private ChatMessageService chatMessageService;
  @Mock private ChatRequestResolver chatRequestResolver;
  @Mock private ChatUsageRecorder chatUsageRecorder;
  @Mock private ToolCallLogRecorder toolCallLogRecorder;
  @Mock private ChatStreamSubscriber chatStreamSubscriber;
  @Mock private StreamConcurrencyGate streamConcurrencyGate;

  private ChatService service;
  private UUID chatId;
  private String userId;
  private RequestDTO request;

  @BeforeEach
  void setUp() {
    service =
        new ChatService(
            chatRepository,
            chatToolRepository,
            providerClientFactory,
            new ChatProperties(),
            chatMessageService,
            chatRequestResolver,
            chatUsageRecorder,
            toolCallLogRecorder,
            chatStreamSubscriber,
            streamConcurrencyGate);
    chatId = UUID.randomUUID();
    userId = "user-123";
    request = new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.5", null, null, false);
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
  }

  @Test
  void sendMessage_withoutProviderKeyPropagatesApiKeyRequiredBeforePersisting() {
    AccessRestrictionException exception =
        new AccessRestrictionException(
            AccessRestrictionException.Reason.API_KEY_REQUIRED, "Add an API key");
    when(chatRequestResolver.resolveCall(userId, request)).thenThrow(exception);

    assertThatThrownBy(() -> service.sendMessage(chatId, userId, request)).isSameAs(exception);

    verify(chatMessageService, never()).persistUserMessage(chatId, "hello", null);
    verify(providerClientFactory, never()).getClient(RequestDTO.Provider.OPENAI, "sk", null);
  }

  @Test
  void streamMessage_withoutProviderKeyDoesNotAcquireStreamSlot() {
    AccessRestrictionException exception =
        new AccessRestrictionException(
            AccessRestrictionException.Reason.API_KEY_REQUIRED, "Add an API key");
    when(chatRequestResolver.resolveCall(userId, request)).thenThrow(exception);

    assertThatThrownBy(() -> service.streamMessage(chatId, userId, request)).isSameAs(exception);

    verify(streamConcurrencyGate, never()).acquire(userId);
  }
}
