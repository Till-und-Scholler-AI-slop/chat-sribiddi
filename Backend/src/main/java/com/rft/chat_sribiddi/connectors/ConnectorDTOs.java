package com.rft.chat_sribiddi.connectors;

import java.time.Instant;
import java.util.List;

public final class ConnectorDTOs {

  private ConnectorDTOs() {}

  public record ConnectorStatusResponse(
      String provider,
      ConnectorStatus status,
      boolean configured,
      boolean connected,
      List<String> scopes,
      String credentialType,
      Instant lastUsedAt,
      boolean planAllowed) {}

  public record OAuthUrlResponse(String url) {}

  public record GooglePickerTokenResponse(String accessToken, String developerKey, String appId) {}

  public record DriveImportRequest(String fileId, String filename, String mimeType) {}
}
