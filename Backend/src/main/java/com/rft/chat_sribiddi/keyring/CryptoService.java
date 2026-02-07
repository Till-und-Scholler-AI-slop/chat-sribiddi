package com.rft.chat_sribiddi.keyring;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Minimal AES/GCM helper to protect stored API keys at rest.
 *
 * <p>Requires a 32-character (256-bit) base64-encoded secret via `ENCRYPTION_SECRET`.
 */
@Component
public class CryptoService {

  private static final String AES = "AES";
  private static final String AES_GCM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH = 128;
  private static final int IV_LENGTH = 12;

  private final SecureRandom secureRandom = new SecureRandom();

  @Value("${encryption.secret}")
  private String secret;

  private SecretKey key;

  @PostConstruct
  void init() {
    byte[] raw = Base64.getDecoder().decode(secret);
    if (raw.length != 32) {
      throw new IllegalStateException(
          "ENCRYPTION_SECRET must decode to 32 bytes (256-bit). Current length: " + raw.length);
    }
    this.key = new SecretKeySpec(raw, AES);
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(AES_GCM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      byte[] combined = new byte[iv.length + cipherText.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encrypt API key", e);
    }
  }

  public String decrypt(String ciphertext) {
    try {
      byte[] all = Base64.getDecoder().decode(ciphertext);
      byte[] iv = new byte[IV_LENGTH];
      byte[] cipherBytes = new byte[all.length - IV_LENGTH];
      System.arraycopy(all, 0, iv, 0, IV_LENGTH);
      System.arraycopy(all, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

      Cipher cipher = Cipher.getInstance(AES_GCM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] plain = cipher.doFinal(cipherBytes);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to decrypt API key", e);
    }
  }
}
