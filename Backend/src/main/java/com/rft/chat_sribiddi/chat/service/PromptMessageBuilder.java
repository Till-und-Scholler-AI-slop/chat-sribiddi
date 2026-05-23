package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PromptMessageBuilder {

  private static final String SYSTEM_PROMPT =
      """
      You are a helpful assistant. Format every answer as clean, renderer-friendly Markdown.
      Use paragraphs, lists, tables, blockquotes, and fenced code blocks with language identifiers when helpful.
      Do not wrap the whole answer in a single code block or use raw HTML unless the user explicitly asks for it.
      """;

  private static final String USER_INSTRUCTION_PREFIX =
      "System instructions for this conversation. Follow them when responding.\n\n";
  private static final String DOCUMENT_CONTEXT_PREFIX =
      "Use these retrieved document excerpts as context when they are relevant. If the excerpts do not contain the answer, say so instead of inventing details.\n\n";

  private final MessageRepository messageRepository;
  private final ChatProperties chatProperties;
  private final MediaValidationService mediaValidationService;

  List<Message> build(PromptMessageRequest request) {
    List<MessageEntity> latestMessages =
        new ArrayList<>(
            messageRepository.findByChatIdOrderByCreatedAtDesc(
                request.chatId(), PageRequest.of(0, contextMessageLimit(request.entitlements()))));

    List<Message> messages = new ArrayList<>();
    String instructionContent = instructionContent(request.customInstructions());
    if (supportsSystemInstruction(request.provider(), request.modelId())) {
      messages.add(new SystemMessage(instructionContent));
    } else {
      messages.add(new UserMessage(USER_INSTRUCTION_PREFIX + instructionContent));
    }

    List<MessageEntity> ordered = latestMessages.reversed();
    for (MessageEntity entity : ordered) {
      if ((!request.mediaAttachments().isEmpty() || hasDocumentContext(request.documentContext()))
          && request.targetUserMessageId() != null
          && entity.getRole() == Role.USER
          && request.targetUserMessageId().equals(entity.getId())) {
        messages.add(
            toUserMessage(
                withDocumentContext(entity.getMessage(), request.documentContext()),
                request.mediaAttachments(),
                request.entitlements()));
        continue;
      }
      messages.add(toChatMessage(entity));
    }
    return messages;
  }

  List<Message> buildStateless(StatelessPromptMessageRequest request) {
    List<Message> messages = new ArrayList<>();
    String instructionContent = instructionContent(request.customInstructions());
    if (supportsSystemInstruction(request.provider(), request.modelId())) {
      messages.add(new SystemMessage(instructionContent));
    } else {
      messages.add(new UserMessage(USER_INSTRUCTION_PREFIX + instructionContent));
    }

    List<StatelessChatMessage> allMessages = new ArrayList<>();
    if (request.history() != null) {
      allMessages.addAll(
          request.history().stream()
              .filter(item -> item != null && item.content() != null && !item.content().isBlank())
              .toList());
    }
    allMessages.add(new StatelessChatMessage(Role.USER, request.currentContent()));

    int limit = contextMessageLimit(request.entitlements());
    int from = Math.max(0, allMessages.size() - limit);
    List<StatelessChatMessage> limited = allMessages.subList(from, allMessages.size());
    for (int i = 0; i < limited.size(); i++) {
      StatelessChatMessage item = limited.get(i);
      boolean isCurrent = i == limited.size() - 1;
      if (isCurrent
          && item.role() == Role.USER
          && (!request.mediaAttachments().isEmpty()
              || hasDocumentContext(request.documentContext()))) {
        messages.add(
            toUserMessage(
                withDocumentContext(item.content(), request.documentContext()),
                request.mediaAttachments(),
                request.entitlements()));
      } else {
        messages.add(toChatMessage(item.role(), item.content()));
      }
    }
    return messages;
  }

  String assistantContext(int messageCount, RequestDTO.Provider provider, String modelId) {
    if (supportsSystemInstruction(provider, modelId)) {
      return "Contains last %d messages including system prompt".formatted(messageCount);
    }
    return "Contains last %d messages (instructions sent as user message)".formatted(messageCount);
  }

  static boolean supportsSystemInstruction(RequestDTO.Provider provider, String modelId) {
    return !(provider == RequestDTO.Provider.GOOGLE
        && modelId != null
        && modelId.startsWith("gemma-"));
  }

  private String instructionContent(String customInstructions) {
    return customInstructions != null && !customInstructions.isBlank()
        ? SYSTEM_PROMPT + "\n\n" + customInstructions
        : SYSTEM_PROMPT;
  }

  private int contextMessageLimit(Entitlements entitlements) {
    int configured = chatProperties.getContextMessageLimit();
    if (entitlements == null || entitlements.contextMessageLimit() <= 0) {
      return Math.max(1, configured);
    }
    return Math.max(1, Math.min(configured, entitlements.contextMessageLimit()));
  }

  private Message toChatMessage(MessageEntity entity) {
    return toChatMessage(entity.getRole(), entity.getMessage());
  }

  private Message toChatMessage(Role role, String text) {
    return switch (role) {
      case ASSISTANT -> new AssistantMessage(text);
      case SYSTEM -> new SystemMessage(text);
      default -> new UserMessage(text);
    };
  }

  private UserMessage toUserMessage(
      MessageEntity entity, List<MediaAttachment> mediaAttachments, Entitlements entitlements) {
    return toUserMessage(entity.getMessage(), mediaAttachments, entitlements);
  }

  private UserMessage toUserMessage(
      String text, List<MediaAttachment> mediaAttachments, Entitlements entitlements) {
    return UserMessage.builder()
        .text(text)
        .media(mediaValidationService.toMediaPayload(mediaAttachments, entitlements))
        .build();
  }

  private String withDocumentContext(String text, String documentContext) {
    if (!hasDocumentContext(documentContext)) {
      return text;
    }
    return DOCUMENT_CONTEXT_PREFIX + documentContext.trim() + "\n\nUser request:\n" + text;
  }

  private boolean hasDocumentContext(String documentContext) {
    return documentContext != null && !documentContext.isBlank();
  }

  record PromptMessageRequest(
      UUID chatId,
      List<MediaAttachment> mediaAttachments,
      Long targetUserMessageId,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      String documentContext) {}

  record StatelessPromptMessageRequest(
      List<StatelessChatMessage> history,
      String currentContent,
      List<MediaAttachment> mediaAttachments,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      String documentContext) {}

  record StatelessChatMessage(Role role, String content) {}
}
