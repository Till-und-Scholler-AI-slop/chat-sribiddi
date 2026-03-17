package com.rft.chat_sribiddi.auth;

import jakarta.validation.constraints.Size;

/**
 * DTO for reading and updating user-level settings.
 *
 * @param customInstructions optional custom instructions appended to the system prompt
 */
public record UserSettingsDTO(
    @Size(max = 4000, message = "customInstructions must be at most 4000 characters")
        String customInstructions) {}
