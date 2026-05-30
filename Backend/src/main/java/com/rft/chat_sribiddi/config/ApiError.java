package com.rft.chat_sribiddi.config;

import java.util.Map;

public record ApiError(String message, String code, Map<String, String> details) {
  public ApiError(String message) {
    this(message, null, null);
  }

  public ApiError(String message, String code) {
    this(message, code, null);
  }
}
