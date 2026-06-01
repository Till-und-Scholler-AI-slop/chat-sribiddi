package com.rft.chat_sribiddi.connectors;

import java.util.Locale;

public enum ConnectorProvider {
  GOOGLE_DRIVE("google-drive"),
  GOOGLE_GMAIL("google-gmail"),
  GOOGLE_CALENDAR("google-calendar");

  private final String id;

  ConnectorProvider(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public String registrationId() {
    return id;
  }

  public static ConnectorProvider parse(String value) {
    String normalized = value.replace('-', '_').toUpperCase(Locale.ROOT);
    return ConnectorProvider.valueOf(normalized);
  }
}
