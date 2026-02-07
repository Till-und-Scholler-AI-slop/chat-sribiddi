package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

  private final ApiKeyRepository repository;
  private final CryptoService cryptoService;

  @Transactional
  public void saveKey(String userId, Provider provider, String apiKey) {
    ApiKeyEntity entity =
        repository.findByUserIdAndProvider(userId, provider).orElseGet(ApiKeyEntity::new);
    entity.setUserId(userId);
    entity.setProvider(provider);
    entity.setApiKey(apiKey);
    entity.setKeyVersion(cryptoService.activeVersion());
    repository.save(entity);
  }

  @Transactional
  public Optional<String> getKey(String userId, Provider provider) {
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

    return Optional.of(apiKey);
  }

  @Transactional
  public void deleteKey(String userId, Provider provider) {
    repository.deleteByUserIdAndProvider(userId, provider);
  }

  @Transactional(readOnly = true)
  public boolean hasKey(String userId, Provider provider) {
    return repository.existsByUserIdAndProvider(userId, provider);
  }

  private boolean needsRotation(ApiKeyEntity entity) {
    Integer currentVersion = entity.getKeyVersion();
    return currentVersion == null || currentVersion < cryptoService.activeVersion();
  }
}
