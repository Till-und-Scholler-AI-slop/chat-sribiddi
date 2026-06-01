package com.rft.chat_sribiddi.tools.credentials;

import com.rft.chat_sribiddi.keyring.CryptoService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ToolCredentialService {

  private final ToolCredentialRepository repository;
  private final CryptoService cryptoService;

  @Transactional
  public void saveSecret(String userId, ToolCredentialProvider provider, String secret) {
    ToolCredentialEntity entity =
        repository.findByUserIdAndProvider(userId, provider).orElseGet(ToolCredentialEntity::new);
    entity.setUserId(userId);
    entity.setProvider(provider);
    entity.setSecret(secret);
    entity.setKeyVersion(cryptoService.activeVersion());
    repository.save(entity);
  }

  @Transactional(readOnly = true)
  public Optional<String> getSecret(String userId, ToolCredentialProvider provider) {
    return repository.findByUserIdAndProvider(userId, provider)
        .map(ToolCredentialEntity::getSecret)
        .filter(secret -> !secret.isBlank());
  }

  @Transactional
  public void deleteSecret(String userId, ToolCredentialProvider provider) {
    repository.deleteByUserIdAndProvider(userId, provider);
  }

  @Transactional(readOnly = true)
  public boolean hasSecret(String userId, ToolCredentialProvider provider) {
    return repository.existsByUserIdAndProvider(userId, provider);
  }
}
