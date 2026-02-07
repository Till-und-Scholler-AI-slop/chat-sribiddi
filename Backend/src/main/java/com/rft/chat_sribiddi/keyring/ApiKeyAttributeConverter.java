package com.rft.chat_sribiddi.keyring;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Service;

@Converter
public class ApiKeyAttributeConverter implements AttributeConverter<String, String> {

  private static CryptoService cryptoService;

  static void initialize(CryptoService service) {
    cryptoService = service;
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    if (attribute == null) {
      return null;
    }
    if (cryptoService == null) {
      throw new IllegalStateException("CryptoService has not been initialized");
    }
    return cryptoService.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    if (cryptoService == null) {
      throw new IllegalStateException("CryptoService has not been initialized");
    }
    return cryptoService.decrypt(dbData);
  }
}

@Service
class ApiKeyAttributeConverterBootstrap {

  ApiKeyAttributeConverterBootstrap(CryptoService cryptoService) {
    ApiKeyAttributeConverter.initialize(cryptoService);
  }
}
