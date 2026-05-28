package com.rft.chat_sribiddi.chat.service;

import java.util.Optional;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

final class ChatResponseExtractor {

  private ChatResponseExtractor() {}

  static String text(ChatResponse response) {
    return Optional.ofNullable(response)
        .map(ChatResponse::getResult)
        .map(Generation::getOutput)
        .map(AbstractMessage::getText)
        .orElse(null);
  }

  static ChatCallResult call(ChatClient.ChatClientRequestSpec promptSpec) {
    ChatClient.CallResponseSpec callResponseSpec = promptSpec.call();
    ResponseEntity<ChatResponse, String> responseEntity =
        callResponseSpec.responseEntity(String.class);
    if (responseEntity != null) {
      return new ChatCallResult(responseEntity.response(), responseEntity.entity());
    }
    ChatResponse response = callResponseSpec.chatResponse();
    return new ChatCallResult(response, text(response));
  }

  static Optional<Usage> usage(ChatResponse response) {
    if (response == null) {
      return Optional.empty();
    }
    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(metadata.getUsage());
  }

  static String reasoningContent(ChatResponse response) {
    return Optional.ofNullable(response)
        .map(ChatResponse::getResult)
        .map(Generation::getOutput)
        .map(AbstractMessage::getMetadata)
        .map(metadata -> metadata.get("reasoningContent"))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(s -> !s.isEmpty())
        .orElse(null);
  }

  static long utf8Length(CharSequence value) {
    long byteLength = 0;
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current <= 0x7F) {
        byteLength++;
      } else if (current <= 0x7FF) {
        byteLength += 2;
      } else if (Character.isHighSurrogate(current)) {
        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
          byteLength += 4;
          i++;
        } else {
          byteLength += 3;
        }
      } else {
        byteLength += 3;
      }
    }
    return byteLength;
  }

  record ChatCallResult(ChatResponse response, String content) {}
}
