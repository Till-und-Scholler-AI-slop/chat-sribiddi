package com.rft.chat_sribiddi.auth;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsService {

  private final UserRepository userRepository;

  public Optional<String> getCustomInstructions(String providerId) {
    return userRepository
        .findByProviderId(providerId)
        .map(User::getCustomInstructions);
  }

  @Transactional
  public void saveCustomInstructions(String providerId, String customInstructions) {
    User user =
        userRepository
            .findByProviderId(providerId)
            .orElseThrow(
                () -> new IllegalStateException("No account found for the current user"));

    String normalized =
        (customInstructions != null && !customInstructions.isBlank())
            ? customInstructions.trim()
            : null;
    user.setCustomInstructions(normalized);
    userRepository.save(user);
    log.debug("Updated custom instructions for providerId={}", providerId);
  }
}
