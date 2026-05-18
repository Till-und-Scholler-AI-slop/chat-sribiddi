package com.rft.chat_sribiddi.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Payload for importing browser-local guest chats into an authenticated account. */
public record ImportChatsRequestDTO(
    @Size(max = 30, message = "import supports up to 30 chats")
        List<@NotNull(message = "chats must not contain null items") @Valid ImportedChat> chats) {

  public record ImportedChat(
      @Size(max = 120, message = "title must be at most 120 characters") String title,
      Instant createdAt,
      @Size(max = 100, message = "import supports up to 100 messages per chat")
          List<@NotNull(message = "messages must not contain null items") @Valid ImportedMessage>
              messages) {}

  public record ImportedMessage(
      @NotBlank(message = "role must not be blank") String role,
      @NotBlank(message = "message must not be blank")
          @Size(max = 20000, message = "message must be at most 20000 characters")
          String message,
      Instant createdAt) {}
}
