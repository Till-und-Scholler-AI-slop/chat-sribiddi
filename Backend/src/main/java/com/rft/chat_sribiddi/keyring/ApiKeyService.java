package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

  private final ApiKeyRepository repository;
  private final CryptoService cryptoService;
  private final ProviderConnectionService providerConnectionService;
  private final ProviderCatalogService providerCatalogService;

  @Autowired
  public ApiKeyService(
      ApiKeyRepository repository,
      CryptoService cryptoService,
      ProviderConnectionService providerConnectionService,
      ProviderCatalogService providerCatalogService) {
    this.repository = repository;
    this.cryptoService = cryptoService;
    this.providerConnectionService = providerConnectionService;
    this.providerCatalogService = providerCatalogService;
  }

  ApiKeyService(ApiKeyRepository repository, CryptoService cryptoService) {
    this.repository = repository;
    this.cryptoService = cryptoService;
    this.providerConnectionService = null;
    this.providerCatalogService = null;
  }

  @Transactional
  public void saveKey(String userId, Provider provider, String apiKey) {
    ApiKeyEntity entity =
        repository.findByUserIdAndProvider(userId, provider).orElseGet(ApiKeyEntity::new);
    entity.setUserId(userId);
    entity.setProvider(provider);
    entity.setApiKey(apiKey);
    entity.setKeyVersion(cryptoService.activeVersion());
    repository.save(entity);
    if (providerConnectionService != null) {
      providerConnectionService.saveApiKey(userId, providerId(provider), apiKey);
    }
  }

  @Transactional
  public Optional<String> getKey(String userId, Provider provider) {
    if (providerConnectionService != null) {
      Optional<String> connectionKey = providerConnectionService.getApiKey(userId, providerId(provider));
      if (connectionKey.isPresent()) {
        return connectionKey;
      }
    }

    Optional<ApiKeyEntity> entityOpt = repository.findByUserIdAndProvider(userId, provider);
    if (entityOpt.isEmpty()) {
      return Optional.empty();
    }

    ApiKeyEntity entity = entityOpt.get();
    String apiKey = entity.getApiKey();

    if (needsRotation(entity)) {
      entity.setApiKey(apiKey);
      entity.setKeyVersion(cryptoService.activeVersion());
      repository.save(entity);
    }

    if (providerConnectionService != null) {
      providerConnectionService.saveApiKey(userId, providerId(provider), apiKey);
    }

    return Optional.of(apiKey);
  }

  @Transactional
  public void deleteKey(String userId, Provider provider) {
    repository.deleteByUserIdAndProvider(userId, provider);
    if (providerConnectionService != null) {
      providerConnectionService.deleteApiKey(userId, providerId(provider));
    }
  }

  @Transactional(readOnly = true)
  public boolean hasKey(String userId, Provider provider) {
    return (providerConnectionService != null
            && providerConnectionService.hasReadyConnection(userId, providerId(provider)))
        || repository.existsByUserIdAndProvider(userId, provider);
  }

  private boolean needsRotation(ApiKeyEntity entity) {
    Integer currentVersion = entity.getKeyVersion();
    return currentVersion == null || currentVersion < cryptoService.activeVersion();
  }

  private String providerId(Provider provider) {
    if (providerCatalogService != null) {
      return providerCatalogService.providerId(provider);
    }
    return switch (provider) {
      case OPENAI -> "openai";
      case GOOGLE -> "google";
      case CLAUDE -> "anthropic";
      case OPENROUTER -> "openrouter";
      case BRAVE_SEARCH -> "brave-search";
    };
  }
}
