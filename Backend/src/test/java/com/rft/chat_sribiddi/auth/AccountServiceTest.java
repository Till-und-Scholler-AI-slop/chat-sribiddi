package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ApiKeyRepository apiKeyRepository;
  @Mock private OAuth2AuthorizedClientService authorizedClientService;

  private AccountService accountService;

  @BeforeEach
  void setUp() {
    accountService =
        new AccountService(
            userRepository,
            chatRepository,
            messageRepository,
            apiKeyRepository,
            authorizedClientService);
  }

  @Test
  void deleteAccount_deletesAllUserDataAndUser() {
    String providerId = "provider-abc";
    User user = new User();
    user.setProviderId(providerId);

    UUID chatId1 = UUID.randomUUID();
    UUID chatId2 = UUID.randomUUID();
    ChatEntity chat1 = new ChatEntity();
    chat1.setChatId(chatId1);
    ChatEntity chat2 = new ChatEntity();
    chat2.setChatId(chatId2);
    List<ChatEntity> chats = List.of(chat1, chat2);

    when(userRepository.findByProviderId(providerId)).thenReturn(Optional.of(user));
    when(chatRepository.findByUserIdOrderByCreatedAtDesc(providerId)).thenReturn(chats);

    accountService.deleteAccount(providerId);

    InOrder inOrder =
        inOrder(apiKeyRepository, messageRepository, chatRepository, userRepository);
    inOrder.verify(apiKeyRepository).deleteByUserId(providerId);
    inOrder.verify(messageRepository).deleteAllByChatIdIn(List.of(chatId1, chatId2));
    inOrder.verify(chatRepository).deleteAll(chats);
    inOrder.verify(userRepository).delete(user);
    verify(authorizedClientService).removeAuthorizedClient("google", providerId);
  }

  @Test
  void deleteAccount_noChats_stillDeletesApiKeysAndUser() {
    String providerId = "provider-xyz";
    User user = new User();
    user.setProviderId(providerId);

    when(userRepository.findByProviderId(providerId)).thenReturn(Optional.of(user));
    when(chatRepository.findByUserIdOrderByCreatedAtDesc(providerId)).thenReturn(List.of());

    accountService.deleteAccount(providerId);

    verify(apiKeyRepository).deleteByUserId(providerId);
    verifyNoInteractions(messageRepository);
    verify(chatRepository, never()).deleteAll(any(List.class));
    verify(userRepository).delete(user);
    verify(authorizedClientService).removeAuthorizedClient("google", providerId);
  }

  @Test
  void deleteAccount_userNotFound_throwsIllegalStateException() {
    String providerId = "unknown-user";
    when(userRepository.findByProviderId(providerId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.deleteAccount(providerId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No account found");

    verifyNoInteractions(apiKeyRepository, chatRepository, messageRepository);
    verifyNoInteractions(authorizedClientService);
  }
}
