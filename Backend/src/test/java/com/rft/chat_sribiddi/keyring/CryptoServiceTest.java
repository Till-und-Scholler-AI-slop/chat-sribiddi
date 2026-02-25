package com.rft.chat_sribiddi.keyring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

  @Test
  void encryptAndDecrypt_useVersionedCiphertext() {
    CryptoService cryptoService = createService(1, base64("12345678901234567890123456789012"), "");

    String encrypted = cryptoService.encrypt("sk-test");

    assertThat(encrypted).startsWith("v1:");
    assertThat(cryptoService.decrypt(encrypted)).isEqualTo("sk-test");
  }

  @Test
  void decrypt_supportsOlderVersionWhenConfigured() {
    String v1Secret = base64("12345678901234567890123456789012");
    String v2Secret = base64("abcdefghijklmnopqrstuvwxyzABCDEF");

    CryptoService v1Service = createService(1, v1Secret, "");
    String v1Encrypted = v1Service.encrypt("old-key");

    CryptoService v2Service = createService(2, v2Secret, "1:" + v1Secret);

    assertThat(v2Service.decrypt(v1Encrypted)).isEqualTo("old-key");
    assertThat(v2Service.encrypt("new-key")).startsWith("v2:");
  }

  private static CryptoService createService(int activeVersion, String secret, String decryptionSecrets) {
    KeyringEncryptionProperties properties = new KeyringEncryptionProperties();
    properties.setActiveKeyVersion(activeVersion);
    properties.setSecret(secret);
    properties.setDecryptionSecrets(decryptionSecrets);

    CryptoService service = new CryptoService(properties);
    service.init();
    return service;
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
