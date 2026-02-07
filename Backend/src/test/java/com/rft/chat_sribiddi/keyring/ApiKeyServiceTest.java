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

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

  @Mock private ApiKeyRepository repository;

  private ApiKeyService apiKeyService;
  private CryptoService cryptoService;

  @BeforeEach
  void setUp() {
    KeyringEncryptionProperties properties = new KeyringEncryptionProperties();
    properties.setSecret("MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="); // base64 32 bytes
    cryptoService = new CryptoService(properties);
    cryptoService.init();
    apiKeyService = new ApiKeyService(repository, cryptoService);
  }

  @Test
  void saveAndRetrieveKey_roundTripsThroughService() {
    ApiKeyEntity entity = new ApiKeyEntity();
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));

    apiKeyService.saveKey("u1", RequestDTO.Provider.OPENAI, "sk-test");

    assertThat(entity.getApiKey()).isEqualTo("sk-test");
    assertThat(entity.getKeyVersion()).isEqualTo(1);

    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));
    Optional<String> retrieved = apiKeyService.getKey("u1", RequestDTO.Provider.OPENAI);

    assertThat(retrieved).contains("sk-test");
  }

  @Test
  void saveKey_overwritesExisting() {
    ApiKeyEntity existing = new ApiKeyEntity();
    existing.setApiKey("old");
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.GOOGLE))
        .thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    apiKeyService.saveKey("u1", RequestDTO.Provider.GOOGLE, "new-key");

    ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getApiKey()).isEqualTo("new-key");
    assertThat(captor.getValue().getKeyVersion()).isEqualTo(1);
  }

  @Test
  void getKey_rotatesWhenStoredVersionIsOlder() {
    KeyringEncryptionProperties properties = new KeyringEncryptionProperties();
    properties.setSecret("MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="); // base64 32 bytes
    properties.setActiveKeyVersion(2);
    CryptoService rotatingCryptoService = new CryptoService(properties);
    rotatingCryptoService.init();
    ApiKeyService rotatingService = new ApiKeyService(repository, rotatingCryptoService);

    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setApiKey("stored-key");
    entity.setKeyVersion(1);
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Optional<String> key = rotatingService.getKey("u1", RequestDTO.Provider.OPENAI);

    assertThat(key).contains("stored-key");
    ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getKeyVersion()).isEqualTo(2);
    assertThat(captor.getValue().getApiKey()).isEqualTo("stored-key");
  }

  @Test
  void getKey_doesNotRotateWhenVersionIsCurrent() {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.setApiKey("stored-key");
    entity.setKeyVersion(1);
    when(repository.findByUserIdAndProvider("u1", RequestDTO.Provider.OPENAI))
        .thenReturn(Optional.of(entity));

    Optional<String> key = apiKeyService.getKey("u1", RequestDTO.Provider.OPENAI);

    assertThat(key).contains("stored-key");
    verify(repository, never()).save(any());
  }
}
