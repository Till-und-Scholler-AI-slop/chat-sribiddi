package com.rft.chat_sribiddi.chat.dto;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.ChatRuntimeOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Stateless anonymous chat request. History is supplied by the browser from localStorage. */
public record AnonymousChatRequestDTO(
    @NotBlank(message = "content must not be blank")
        @Size(max = 20000, message = "content must be at most 20000 characters")
        String content,
    @NotNull(message = "provider is required") Provider provider,
    @NotBlank(message = "modelId must not be blank") String modelId,
    @Size(max = 100, message = "history supports up to 100 messages")
        List<@NotNull(message = "history items must not be null") @Valid HistoryMessage> history,
    @Size(max = 5, message = "media supports up to 5 attachments")
        List<@NotNull(message = "media items must not be null") @Valid MediaAttachment> media,
    @Valid ChatRuntimeOptions options) {

  public AnonymousChatRequestDTO(
      String content,
      Provider provider,
      String modelId,
      List<HistoryMessage> history,
      List<MediaAttachment> media) {
    this(content, provider, modelId, history, media, null);
  }

  public record HistoryMessage(
      @NotBlank(message = "role must not be blank") String role,
      @NotBlank(message = "message must not be blank")
          @Size(max = 20000, message = "message must be at most 20000 characters")
          String message) {}
}
