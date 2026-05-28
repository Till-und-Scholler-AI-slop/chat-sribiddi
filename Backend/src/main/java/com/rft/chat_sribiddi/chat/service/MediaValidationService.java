package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.billing.Entitlements;
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
    return validateAttachments(mediaAttachments, null);
  }

  public List<MediaAttachment> validateAttachments(
      List<MediaAttachment> mediaAttachments, Entitlements entitlements) {
    validateAndConvertAttachments(mediaAttachments, entitlements);
    return mediaAttachments == null ? List.of() : mediaAttachments;
  }

  public List<ValidatedMediaAttachment> validateAndConvertAttachments(
      List<MediaAttachment> mediaAttachments, Entitlements entitlements) {
    if (mediaAttachments == null || mediaAttachments.isEmpty()) {
      return List.of();
    }

    int maxAttachments = maxAttachments(entitlements);
    if (mediaAttachments.size() > maxAttachments) {
      throw new IllegalArgumentException(
          "media supports up to %d attachments".formatted(maxAttachments));
    }

    long totalBytes = 0L;
    long maxTotalBytes = maxTotalBytes(entitlements);
    List<ValidatedMediaAttachment> validated = new ArrayList<>(mediaAttachments.size());
    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      if (attachment == null) {
        throw new IllegalArgumentException("media[%d] must not be null".formatted(i));
      }
      ValidatedMediaAttachment converted = validateAttachmentPayload(i, attachment, entitlements);
      totalBytes += converted.sizeBytes();
      if (totalBytes > maxTotalBytes) {
        throw new IllegalArgumentException(
            "combined media size exceeds %d bytes".formatted(maxTotalBytes));
      }
      validated.add(converted);
    }

    return List.copyOf(validated);
  }

  public List<Media> toMediaPayload(List<MediaAttachment> mediaAttachments) {
    return toMediaPayload(mediaAttachments, null);
  }

  public List<Media> toMediaPayload(
      List<MediaAttachment> mediaAttachments, Entitlements entitlements) {
    return mediaPayload(validateAndConvertAttachments(mediaAttachments, entitlements));
  }

  public List<Media> mediaPayload(List<ValidatedMediaAttachment> mediaAttachments) {
    if (mediaAttachments == null || mediaAttachments.isEmpty()) {
      return List.of();
    }
    return mediaAttachments.stream().map(ValidatedMediaAttachment::media).toList();
  }

  private ValidatedMediaAttachment validateAttachmentPayload(
      int index, MediaAttachment attachment, Entitlements entitlements) {
    String normalized = normalizeEncodedData(attachment.data());
    long estimatedDecodedBytes = estimateDecodedBytes(normalized);
    long maxAttachmentBytes = maxAttachmentBytes(entitlements);
    if (estimatedDecodedBytes > maxAttachmentBytes) {
      throw new IllegalArgumentException(
          "media[%d] exceeds %d bytes".formatted(index, maxAttachmentBytes));
    }

    byte[] content;
    try {
      content = Base64.getDecoder().decode(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("media[%d].data is not valid base64".formatted(index), e);
    }

    if (content.length > maxAttachmentBytes) {
      throw new IllegalArgumentException(
          "media[%d] exceeds %d bytes".formatted(index, maxAttachmentBytes));
    }

    org.springframework.util.MimeType mimeType;
    try {
      mimeType = MimeTypeUtils.parseMimeType(attachment.mimeType());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("media[%d].mimeType is not valid".formatted(index), e);
    }
    Media media =
        Media.builder()
            .mimeType(mimeType)
            .data(new ByteArrayResource(content))
            .build();
    return new ValidatedMediaAttachment(attachment.mimeType(), media, content.length);
  }

  private int maxAttachments(Entitlements entitlements) {
    int global = Math.max(0, chatProperties.getMaxMediaAttachments());
    if (entitlements == null || entitlements.maxAttachments() <= 0) {
      return global;
    }
    return Math.min(global, entitlements.maxAttachments());
  }

  private long maxAttachmentBytes(Entitlements entitlements) {
    long global = Math.max(0L, chatProperties.getMaxMediaAttachmentBytes());
    if (entitlements == null || entitlements.maxAttachmentBytes() <= 0) {
      return global;
    }
    return Math.min(global, entitlements.maxAttachmentBytes());
  }

  private long maxTotalBytes(Entitlements entitlements) {
    long global = Math.max(0L, chatProperties.getMaxMediaTotalBytes());
    if (entitlements == null || entitlements.maxTotalAttachmentBytes() <= 0) {
      return global;
    }
    return Math.min(global, entitlements.maxTotalAttachmentBytes());
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

  public record ValidatedMediaAttachment(String mimeType, Media media, long sizeBytes) {}
}
