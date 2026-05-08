package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
@RequiredArgsConstructor
public class MediaValidationService {

  private final ChatProperties chatProperties;

  public List<MediaAttachment> validateAttachments(List<MediaAttachment> mediaAttachments) {
    if (mediaAttachments == null || mediaAttachments.isEmpty()) {
      return List.of();
    }

    if (mediaAttachments.size() > chatProperties.getMaxMediaAttachments()) {
      throw new IllegalArgumentException(
          "media supports up to %d attachments".formatted(chatProperties.getMaxMediaAttachments()));
    }

    for (int i = 0; i < mediaAttachments.size(); i++) {
      if (mediaAttachments.get(i) == null) {
        throw new IllegalArgumentException("media[%d] must not be null".formatted(i));
      }
    }

    return mediaAttachments;
  }

  public List<Media> toMediaPayload(List<MediaAttachment> mediaAttachments) {
    long totalBytes = 0;
    List<Media> media = new ArrayList<>(mediaAttachments.size());

    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      String normalized = normalizeEncodedData(attachment.data());
      long estimatedDecodedBytes = estimateDecodedBytes(normalized);

      if (estimatedDecodedBytes > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes"
                .formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      totalBytes += estimatedDecodedBytes;
      if (totalBytes > chatProperties.getMaxMediaTotalBytes()) {
        throw new IllegalArgumentException(
            "combined media size exceeds %d bytes".formatted(chatProperties.getMaxMediaTotalBytes()));
      }

      byte[] content;
      try {
        content = Base64.getDecoder().decode(normalized);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("media[%d].data is not valid base64".formatted(i), e);
      }

      if (content.length > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes".formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      media.add(
          new Media(
              MimeTypeUtils.parseMimeType(attachment.mimeType()), new ByteArrayResource(content)));
    }

    return media;
  }

  static String normalizeEncodedData(String encodedData) {
    int commaIndex = encodedData.indexOf(',');
    if (encodedData.startsWith("data:") && commaIndex > 0) {
      return encodedData.substring(commaIndex + 1);
    }
    return encodedData;
  }

  static long estimateDecodedBytes(String normalizedBase64) {
    int length = normalizedBase64.length();
    int padding = 0;
    if (length > 0 && normalizedBase64.charAt(length - 1) == '=') {
      padding++;
      if (length > 1 && normalizedBase64.charAt(length - 2) == '=') {
        padding++;
      }
    }
    return ((long) length * 3 / 4) - padding;
  }
}
