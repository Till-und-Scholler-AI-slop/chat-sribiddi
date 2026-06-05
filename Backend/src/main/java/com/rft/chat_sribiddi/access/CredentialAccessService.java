package com.rft.chat_sribiddi.access;

import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.modelcatalog.AvailabilityFlag;
import com.rft.chat_sribiddi.modelcatalog.ModelCatalogProperties.ModelOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CredentialAccessService {

  public static final String OPENROUTER_FREE_MODEL_ID = "openrouter/free";

  private final ApiKeyService apiKeyService;
  private final AccessProperties accessProperties;
  private final Environment environment;

  public ResolvedCredential resolveUserCredential(String userId, Provider provider, String modelId) {
    validateChatProvider(provider);
    validateModelId(modelId);
    if (userId == null || userId.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Authentication is required to use provider API keys");
    }
    Optional<String> userKey = apiKeyService.getKey(userId, provider).filter(key -> !key.isBlank());
    if (userKey.isEmpty()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.API_KEY_REQUIRED,
          "Add a %s API key in Settings before using this model".formatted(provider.name()));
    }
    return new ResolvedCredential(userKey.get(), CredentialSource.USER_KEY, authenticatedLimits());
  }

  public ResolvedCredential resolveGuestOpenRouterFree(String modelId) {
    validateModelId(modelId);
    if (!OPENROUTER_FREE_MODEL_ID.equals(modelId)) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MODEL_NOT_ALLOWED,
          "Anonymous chat is restricted to %s".formatted(OPENROUTER_FREE_MODEL_ID));
    }
    String serverKey = environment.getProperty("OPENROUTER_API_KEY");
    if (serverKey == null || serverKey.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED,
          "OpenRouter Free is not configured on the server");
    }
    return new ResolvedCredential(serverKey, CredentialSource.GUEST_OPENROUTER_FREE, guestLimits());
  }

  public Optional<String> guestOpenRouterApiKey() {
    String serverKey = environment.getProperty("OPENROUTER_API_KEY");
    return serverKey == null || serverKey.isBlank() ? Optional.empty() : Optional.of(serverKey);
  }

  public AccessLimits authenticatedLimits() {
    return accessProperties.authenticatedLimits();
  }

  public AccessLimits guestLimits() {
    return accessProperties.guestLimits();
  }

  public List<ModelOption> annotateModels(
      String userId, Provider provider, List<ModelOption> models, boolean hasUserKey) {
    if (models == null || models.isEmpty()) {
      return models == null ? List.of() : models;
    }
    Objects.requireNonNull(provider, "provider must not be null");
    return models.stream()
        .filter(Objects::nonNull)
        .map(
            option -> {
              ModelOption copy = option.copy();
              copy.setAvailability(availability(userId, provider, option, hasUserKey));
              return copy;
            })
        .toList();
  }

  private AvailabilityFlag availability(
      String userId, Provider provider, ModelOption option, boolean hasUserKey) {
    if (hasUserKey) {
      return AvailabilityFlag.ALWAYS;
    }
    if ((userId == null || userId.isBlank())
        && provider == Provider.OPENROUTER
        && OPENROUTER_FREE_MODEL_ID.equals(option.getId())) {
      return AvailabilityFlag.ALWAYS;
    }
    return AvailabilityFlag.BYOK_ONLY;
  }

  private void validateModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new AccessRestrictionException(
          AccessRestrictionException.Reason.MODEL_NOT_ALLOWED, "Model id must not be blank");
    }
  }

  private void validateChatProvider(Provider provider) {
    Objects.requireNonNull(provider, "provider must not be null");
    if (provider == Provider.BRAVE_SEARCH) {
      throw new IllegalArgumentException("BRAVE_SEARCH is not a chat model provider");
    }
  }

  public record ResolvedCredential(
      String apiKey, CredentialSource credentialSource, AccessLimits limits) {
    public ResolvedCredential {
      Objects.requireNonNull(apiKey, "apiKey must not be null");
      Objects.requireNonNull(credentialSource, "credentialSource must not be null");
      Objects.requireNonNull(limits, "limits must not be null");
    }
  }
}
