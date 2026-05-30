package com.rft.chat_sribiddi.chat.service;

import org.springframework.dao.DataIntegrityViolationException;

public final class DbConstraintChecker {
  private static final String CHAT_FK_CONSTRAINT = "messages_chat_id_fkey";

  private DbConstraintChecker() {}

  public static boolean isChatFkViolation(DataIntegrityViolationException ex) {
    Throwable cause = ex.getCause();
    return cause != null && cause.getMessage() != null
        && cause.getMessage().contains(CHAT_FK_CONSTRAINT);
  }
}
