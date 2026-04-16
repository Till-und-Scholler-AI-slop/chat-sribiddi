package com.rft.chat_sribiddi.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetChatToolRequestDTO(
    @NotBlank(message = "toolName must not be blank") String toolName,
    @NotNull(message = "enabled must not be null") boolean enabled) {}
