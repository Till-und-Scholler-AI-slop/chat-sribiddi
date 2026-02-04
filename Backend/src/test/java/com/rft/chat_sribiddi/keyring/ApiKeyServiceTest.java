package com.rft.chat_sribiddi.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

  @Mock private ApiKeyRepository repository;

  private ApiKeyService apiKeyService;
  private CryptoService cryptoService;

  @BeforeEach
  void setUp() {
    cryptoService = new CryptoService();
    ReflectionTestUtils.setField(
        cryptoService, "secret", "c2VjdXJlLXNlY3JldC1zZWN1cmUtc2VjcmV0LXNlY3JldA=="); // base64 32 bytes
    cryptoService.init();

    apiKeyService = new ApiKeyService(repository, cryptoService);
  }

  @Test
  void saveAndRetrieveKey_roundTripsEncrypted() {
    ApiKeyEntity entity = new ApiKeyEntity();
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));

    apiKeyService.saveKey("u1", RequestDTO.Provider.OPENAI, "sk-test");

    assertThat(entity.getEncryptedKey()).isNotEqualTo("sk-test");

    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));
    Optional<String> retrieved = apiKeyService.getKey("u1", RequestDTO.Provider.OPENAI);

    assertThat(retrieved).contains("sk-test");
  }

  @Test
  void saveKey_overwritesExisting() {
    ApiKeyEntity existing = new ApiKeyEntity();
    existing.setEncryptedKey("old");
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.GOOGLE))
        .thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    apiKeyService.saveKey("u1", RequestDTO.Provider.GOOGLE, "new-key");

    ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getEncryptedKey()).isNotEqualTo("new-key"); // encrypted
  }
}
