package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaValidationServiceTest {

  @Test
  void validateAttachments_enforcesTierAttachmentCountBelowGlobalLimit() {
    ChatProperties properties = new ChatProperties();
    properties.setMaxMediaAttachments(5);
    MediaValidationService service = new MediaValidationService(properties);

    assertThatThrownBy(
            () ->
                service.validateAttachments(
                    List.of(validAttachment("a"), validAttachment("b")), entitlements(1, 1024, 2048)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("up to 1 attachments");
  }

  @Test
  void validateAttachments_rejectsInvalidBase64BeforeMediaPayloadConversion() {
    MediaValidationService service = new MediaValidationService(new ChatProperties());

    assertThatThrownBy(
            () ->
                service.validateAttachments(
                    List.of(new MediaAttachment("image/png", "not-valid-base64")),
                    entitlements(5, 1024, 2048)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not valid base64");
  }

  @Test
  void validateAndConvertAttachments_returnsSpringAiMediaPayload() {
    MediaValidationService service = new MediaValidationService(new ChatProperties());

    var validated =
        service.validateAndConvertAttachments(
            List.of(validAttachment("hello")), entitlements(5, 1024, 2048));

    assertThat(validated).hasSize(1);
    assertThat(validated.getFirst().mimeType()).isEqualTo("image/png");
    assertThat(validated.getFirst().sizeBytes()).isEqualTo(5);
    assertThat(service.mediaPayload(validated))
        .singleElement()
        .satisfies(
            media -> {
              assertThat(media.getMimeType().toString()).isEqualTo("image/png");
              assertThat(media.getDataAsByteArray()).containsExactly("hello".getBytes());
            });
  }

  private static MediaAttachment validAttachment(String data) {
    return new MediaAttachment("image/png", Base64.getEncoder().encodeToString(data.getBytes()));
  }

  private static AccessLimits entitlements(
      int maxAttachments, long maxAttachmentBytes, long maxTotalAttachmentBytes) {
    return new AccessLimits(
        maxAttachments,
        maxAttachmentBytes,
        maxTotalAttachmentBytes,
        10,
        java.util.Set.of(),
        1);
  }
}
