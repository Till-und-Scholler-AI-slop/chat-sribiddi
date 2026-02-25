package com.rft.chat_sribiddi.keyring;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * AES helper backed by Spring Security crypto.
 *
 * <p>Stored values are prefixed as {@code v<version>:<ciphertext>} to support key rotation.
 * Legacy ciphertext produced by the previous JCE implementation is still readable.
 */
@Component
@RequiredArgsConstructor
public class CryptoService {

  private static final String AES = "AES";
  private static final String AES_GCM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH = 128;
  private static final int IV_LENGTH = 12;
  private static final Pattern VERSIONED_CIPHERTEXT = Pattern.compile("^v(\\d+):(.+)$");

  private final SecureRandom secureRandom = new SecureRandom();
  private final KeyringEncryptionProperties properties;

  private final Map<Integer, TextEncryptor> decryptors = new HashMap<>();
  private byte[] activeSecretBytes;
  private TextEncryptor activeEncryptor;

  @PostConstruct
  void init() {
    int activeVersion = properties.getActiveKeyVersion();
    if (activeVersion < 1) {
      throw new IllegalStateException("encryption.active-key-version must be >= 1");
    }
    activeSecretBytes = decodeSecret(properties.getSecret(), "encryption.secret");
    decryptors.put(activeVersion, createEncryptor(activeSecretBytes, activeVersion));

    String extraSecrets = properties.getDecryptionSecrets();
    if (extraSecrets != null && !extraSecrets.isBlank()) {
      for (String token : extraSecrets.split(",")) {
        String entry = token.trim();
        if (entry.isEmpty()) {
          continue;
        }
        String[] parts = entry.split(":", 2);
        if (parts.length != 2) {
          throw new IllegalStateException(
              "Invalid encryption.decryption-secrets entry: " + entry);
        }
        int version;
        try {
          version = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ex) {
          throw new IllegalStateException("Invalid key version in decryption secret: " + entry, ex);
        }
        decryptors.putIfAbsent(version, createEncryptor(decodeSecret(parts[1].trim(), entry), version));
      }
    }

    activeEncryptor = decryptors.get(activeVersion);
    if (activeEncryptor == null) {
      throw new IllegalStateException("No active encryptor configured for version " + activeVersion);
    }
  }

  public int activeVersion() {
    return properties.getActiveKeyVersion();
  }

  public String encrypt(String plaintext) {
    try {
      if (plaintext == null || plaintext.isBlank()) {
        return plaintext;
      }
      String cipherText = activeEncryptor.encrypt(plaintext);
      return "v" + activeVersion() + ":" + cipherText;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encrypt API key", e);
    }
  }

  public String decrypt(String ciphertext) {
    try {
      if (ciphertext == null || ciphertext.isBlank()) {
        return ciphertext;
      }

      Matcher matcher = VERSIONED_CIPHERTEXT.matcher(ciphertext);
      if (matcher.matches()) {
        int version = Integer.parseInt(matcher.group(1));
        TextEncryptor decryptor = decryptors.get(version);
        if (decryptor == null) {
          throw new IllegalStateException("No decryption key configured for version " + version);
        }
        return decryptor.decrypt(matcher.group(2));
      }

      return decryptLegacy(ciphertext);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to decrypt API key", e);
    }
  }

  private byte[] decodeSecret(String value, String source) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(source + " must be configured");
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(source + " must be base64-encoded", ex);
    }
    if (raw.length != 32) {
      throw new IllegalStateException(
          source + " must decode to 32 bytes (256-bit). Current length: " + raw.length);
    }
    return raw;
  }

  private TextEncryptor createEncryptor(byte[] secretBytes, int version) {
    String password = Base64.getEncoder().encodeToString(secretBytes);
    String salt = deriveSalt(secretBytes, version);
    return Encryptors.delux(password, salt);
  }

  private String deriveSalt(byte[] secretBytes, int version) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(secretBytes);
      digest.update((byte) version);
      digest.update("chat-sribiddi-keyring".getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(Arrays.copyOf(digest.digest(), 8));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to derive encryption salt", e);
    }
  }

  /**
   * Backward compatibility for ciphertexts generated before the versioned Spring-Crypto format.
   */
  private String decryptLegacy(String ciphertext) throws Exception {
    byte[] all = Base64.getDecoder().decode(ciphertext);
    if (all.length <= IV_LENGTH) {
      throw new IllegalStateException("Ciphertext is too short");
    }

    byte[] iv = new byte[IV_LENGTH];
    byte[] cipherBytes = new byte[all.length - IV_LENGTH];
    System.arraycopy(all, 0, iv, 0, IV_LENGTH);
    System.arraycopy(all, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

    SecretKey legacyKey = new SecretKeySpec(activeSecretBytes, AES);
    Cipher cipher = Cipher.getInstance(AES_GCM);
    cipher.init(Cipher.DECRYPT_MODE, legacyKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
    byte[] plain = cipher.doFinal(cipherBytes);
    return new String(plain, StandardCharsets.UTF_8);
  }
}
