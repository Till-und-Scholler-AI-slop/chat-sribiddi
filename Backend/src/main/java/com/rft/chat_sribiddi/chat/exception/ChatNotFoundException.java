package com.rft.chat_sribiddi.chat.exception;

import java.util.UUID;

public class ChatNotFoundException extends RuntimeException {
  public ChatNotFoundException(UUID chatId) {
    super("Chat not found: " + chatId);
  }
}
