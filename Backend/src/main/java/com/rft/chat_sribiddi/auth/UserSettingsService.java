package com.rft.chat_sribiddi.auth;

import java.util.Objects;
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

  public Optional<String> getCustomInstructions(String userId) {
    return userRepository
        .findByProviderId(userId)
        .map(User::getCustomInstructions);
  }

  @Transactional
  public void saveCustomInstructions(String userId, String customInstructions) {
    User user =
        userRepository
            .findByProviderId(userId)
            .orElseGet(() -> {
              User newUser = new User();
              newUser.setProviderId(userId);
              return userRepository.save(newUser);
            });

    String normalized =
        (customInstructions != null && !customInstructions.isBlank())
            ? customInstructions.trim()
            : null;
    user.setCustomInstructions(normalized);
    userRepository.save(user);
    log.debug("Updated custom instructions for userId={}", userId);
  }

  @Transactional
  public BillingMode saveBillingModePreference(String userId, BillingMode billingMode) {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(billingMode, "billingMode must not be null");

    User user =
        userRepository
            .findByProviderId(userId)
            .orElseGet(() -> {
              User newUser = new User();
              newUser.setProviderId(userId);
              return userRepository.save(newUser);
            });

    Plan plan = user.getPlan() == null ? Plan.FREE : user.getPlan();
    if (billingMode == BillingMode.MANAGED && plan == Plan.FREE) {
      throw new IllegalArgumentException("Managed billing mode requires Premium or Max plan");
    }

    user.setBillingModePreference(billingMode);
    userRepository.save(user);
    log.debug("Updated billing mode preference for userId={}", userId);
    return billingMode;
  }
}
