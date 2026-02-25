package com.rft.chat_sribiddi.keyring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "encryption")
public class KeyringEncryptionProperties {

  /**
   * Base64-encoded 256-bit active encryption secret.
   */
  private String secret;

  /**
   * Version written for newly stored keys.
   */
  private int activeKeyVersion = 1;

  /**
   * Optional extra decryption secrets in `version:base64Secret` format, comma separated.
   */
  private String decryptionSecrets = "";
}
