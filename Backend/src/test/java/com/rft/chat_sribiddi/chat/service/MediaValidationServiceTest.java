package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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

  private static MediaAttachment validAttachment(String data) {
    return new MediaAttachment("image/png", Base64.getEncoder().encodeToString(data.getBytes()));
  }

  private static Entitlements entitlements(
      int maxAttachments, long maxAttachmentBytes, long maxTotalAttachmentBytes) {
    return new Entitlements(
        Plan.FREE,
        maxAttachments,
        maxAttachmentBytes,
        maxTotalAttachmentBytes,
        10,
        Set.of(),
        50,
        1,
        0.0,
        0.0);
  }
}
