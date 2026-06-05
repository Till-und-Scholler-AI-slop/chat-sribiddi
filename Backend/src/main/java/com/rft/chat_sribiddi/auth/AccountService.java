package com.rft.chat_sribiddi.auth;

import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyRepository;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

  private static final String GOOGLE_REGISTRATION_ID = "google";

  private final UserRepository userRepository;
  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ApiKeyRepository apiKeyRepository;
  private final ProviderConnectionService providerConnectionService;
  private final OAuth2AuthorizedClientService authorizedClientService;

  @Transactional
  public void deleteAccount(String providerId) {
    User user =
        userRepository
            .findByProviderId(providerId)
            .orElseThrow(
                () -> new IllegalStateException("No account found for the current user"));

    apiKeyRepository.deleteByUserId(providerId);
    providerConnectionService.deleteByUserId(providerId);

    messageRepository.deleteAllByUserId(providerId);
    chatRepository.deleteByUserId(providerId);

    userRepository.delete(user);

    log.info("Deleted account for providerId={}", providerId);

    try {
      authorizedClientService.removeAuthorizedClient(GOOGLE_REGISTRATION_ID, providerId);
    } catch (Exception e) {
      log.warn("Failed to remove OAuth2 authorized client for providerId={}", providerId, e);
    }
  }
}
