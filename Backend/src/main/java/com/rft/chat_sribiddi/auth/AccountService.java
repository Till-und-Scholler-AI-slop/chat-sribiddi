package com.rft.chat_sribiddi.auth;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

  private final UserRepository userRepository;
  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ApiKeyRepository apiKeyRepository;

  @Transactional
  public void deleteAccount(String providerId) {
    User user =
        userRepository
            .findByProviderId(providerId)
            .orElseThrow(
                () -> new IllegalStateException("No account found for the current user"));

    apiKeyRepository.deleteByUserId(providerId);

    List<ChatEntity> chats = chatRepository.findByUserIdOrderByCreatedAtDesc(providerId);
    if (!chats.isEmpty()) {
      List<UUID> chatIds = chats.stream().map(ChatEntity::getChatId).toList();
      messageRepository.deleteAllByChatIdIn(chatIds);
      chatRepository.deleteAll(chats);
    }

    userRepository.delete(user);
    log.info("Deleted account for providerId={}", providerId);
  }
}
