package com.rft.chat_sribiddi.tools.credentials;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.EntitlementService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolCredentialResolver {

  private final ToolCredentialService credentialService;
  private final ToolCredentialProperties properties;
  private final EntitlementService entitlementService;

  public Optional<String> resolve(String userId, ToolCredentialProvider provider) {
    Optional<String> userSecret = credentialService.getSecret(userId, provider);
    if (userSecret.isPresent()) {
      return userSecret;
    }
    if (!canUseManaged(userId)) {
      return Optional.empty();
    }
    return serverSecret(provider);
  }

  public boolean hasUsableCredential(String userId, ToolCredentialProvider provider) {
    return resolve(userId, provider).isPresent();
  }

  public boolean hasServerCredential(ToolCredentialProvider provider) {
    return serverSecret(provider).isPresent();
  }

  private boolean canUseManaged(String userId) {
    return entitlementService.get(userId).plan() != Plan.FREE;
  }

  private Optional<String> serverSecret(ToolCredentialProvider provider) {
    String value =
        switch (provider) {
          case OPENAI_IMAGE -> properties.getOpenaiImageApiKey();
          case GITHUB -> properties.getGithubToken();
        };
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }
}
