package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;

public record MessagePreparation(
    String updatedTitle,
    PromptMessageBatch promptBatch) {}
