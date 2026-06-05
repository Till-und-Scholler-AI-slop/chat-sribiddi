package com.rft.chat_sribiddi.access;

public class AccessRestrictionException extends RuntimeException {

  public enum Reason {
    API_KEY_REQUIRED,
    MODEL_NOT_ALLOWED,
    DAILY_CAP_EXCEEDED,
    PARALLEL_STREAMS_EXCEEDED,
    TOOL_NOT_ALLOWED,
    MANAGED_PROVIDER_UNCONFIGURED
  }

  private final Reason reason;

  public AccessRestrictionException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}
