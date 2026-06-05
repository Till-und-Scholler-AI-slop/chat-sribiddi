package com.rft.chat_sribiddi.config;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

/**
 * Generates cache keys for model catalog entries using SHA-256 hashed credential material.
 *
 * <p>Key format: {@code methodName:sha256(credential)}. The credential is hashed to avoid storing
 * raw provider secrets in Redis cache keys.
 */
@Component("modelCatalogKeyGenerator")
public class ModelCatalogKeyGenerator implements KeyGenerator {

  @Override
  public Object generate(Object target, Method method, Object... params) {
    String credential = (String) params[0];
    return method.getName() + ":" + hashApiKey(credential);
  }

  private static String hashApiKey(String credential) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(credential.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
