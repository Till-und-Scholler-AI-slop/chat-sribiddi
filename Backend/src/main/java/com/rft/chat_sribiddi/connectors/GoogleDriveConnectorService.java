package com.rft.chat_sribiddi.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.chat.dto.ChatDocumentDTO;
import com.rft.chat_sribiddi.chat.service.ChatDocumentService;
import com.rft.chat_sribiddi.connectors.ConnectorDTOs.DriveImportRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GoogleDriveConnectorService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DRIVE_API = "https://www.googleapis.com/drive/v3/files/";

  private final ConnectorAccountService connectorAccountService;
  private final ChatDocumentService chatDocumentService;
  private final HttpClient httpClient;

  public ChatDocumentDTO importFile(UUID chatId, String userId, DriveImportRequest request) {
    if (request == null || request.fileId() == null || request.fileId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google Drive fileId is required");
    }
    String accessToken = connectorAccountService.accessToken(userId, ConnectorProvider.GOOGLE_DRIVE);
    JsonNode metadata = metadata(accessToken, request.fileId());
    String mimeType = firstText(request.mimeType(), metadata.path("mimeType").asText(null));
    String filename = firstText(request.filename(), metadata.path("name").asText("drive-document"));
    byte[] data = download(accessToken, request.fileId(), mimeType);
    String importedMimeType = exportedMimeType(mimeType);
    String importedFilename = exportedFilename(filename, mimeType);
    return chatDocumentService.importDocument(chatId, userId, importedFilename, importedMimeType, data);
  }

  private JsonNode metadata(String accessToken, String fileId) {
    String url = DRIVE_API + encodePath(fileId) + "?fields=id,name,mimeType,size";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
    String body = send(request);
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive metadata could not be read", e);
    }
  }

  private byte[] download(String accessToken, String fileId, String mimeType) {
    String url =
        googleWorkspaceMimeType(mimeType)
            ? DRIVE_API + encodePath(fileId) + "/export?mimeType=" + encode(exportedMimeType(mimeType))
            : DRIVE_API + encodePath(fileId) + "?alt=media";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
    try {
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive file download failed");
      }
      return response.body();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive file download failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive file download interrupted", e);
    }
  }

  private String send(HttpRequest request) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive request failed");
      }
      return response.body();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Drive request interrupted", e);
    }
  }

  private static boolean googleWorkspaceMimeType(String mimeType) {
    return mimeType != null && mimeType.startsWith("application/vnd.google-apps.");
  }

  private static String exportedMimeType(String mimeType) {
    if ("application/vnd.google-apps.spreadsheet".equals(mimeType)) {
      return "text/csv";
    }
    if (googleWorkspaceMimeType(mimeType)) {
      return "text/plain";
    }
    return mimeType;
  }

  private static String exportedFilename(String filename, String mimeType) {
    if ("application/vnd.google-apps.spreadsheet".equals(mimeType)) {
      return filename.endsWith(".csv") ? filename : filename + ".csv";
    }
    if (googleWorkspaceMimeType(mimeType)) {
      return filename.endsWith(".txt") ? filename : filename + ".txt";
    }
    return filename;
  }

  private static String firstText(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String encodePath(String value) {
    return encode(value).replace("+", "%20");
  }
}
