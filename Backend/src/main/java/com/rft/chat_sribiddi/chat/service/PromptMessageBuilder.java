package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
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

  private final MessageRepository messageRepository;
  private final ChatProperties chatProperties;

  List<Message> build(PromptMessageRequest request) {
    return buildMessages(request, true);
  }

  PromptMessageBatch buildBatch(PromptMessageRequest request) {
    List<Message> advisorMessages = buildMessages(request, false);
    if (PromptMessageBatch.canUseAdvisors(advisorMessages, request.targetUserMessageId())) {
      return PromptMessageBatch.from(
          advisorMessages, request.targetUserMessageId(), request.retrievedDocuments());
    }
    List<Message> combinedMessages =
        hasDocumentContext(request.retrievedDocuments())
            ? buildMessages(request, true)
            : advisorMessages;
    return PromptMessageBatch.combined(combinedMessages);
  }

  private List<Message> buildMessages(PromptMessageRequest request, boolean inlineDocumentContext) {
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
      if ((!request.media().isEmpty()
              || (inlineDocumentContext && hasDocumentContext(request.retrievedDocuments())))
          && request.targetUserMessageId() != null
          && entity.getRole() == Role.USER
          && request.targetUserMessageId().equals(entity.getId())) {
        String text =
            inlineDocumentContext
                ? DocumentContextFormatter.withDocumentContext(
                    entity.getMessage(), request.retrievedDocuments())
                : entity.getMessage();
        messages.add(
            toUserMessage(
                text,
                request.media(),
                request.entitlements(),
                entity.getId()));
        continue;
      }
      messages.add(toChatMessage(entity));
    }
    return messages;
  }

  List<Message> buildStateless(StatelessPromptMessageRequest request) {
    return buildStatelessMessages(request, true);
  }

  PromptMessageBatch buildStatelessBatch(StatelessPromptMessageRequest request) {
    List<Message> advisorMessages = buildStatelessMessages(request, false);
    return PromptMessageBatch.from(advisorMessages, null, request.retrievedDocuments());
  }

  private List<Message> buildStatelessMessages(
      StatelessPromptMessageRequest request, boolean inlineDocumentContext) {
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
          && (!request.media().isEmpty()
              || (inlineDocumentContext && hasDocumentContext(request.retrievedDocuments())))) {
        String text =
            inlineDocumentContext
                ? DocumentContextFormatter.withDocumentContext(
                    item.content(), request.retrievedDocuments())
                : item.content();
        messages.add(
            toUserMessage(
                text,
                request.media(),
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
    return switch (entity.getRole()) {
      case ASSISTANT ->
          AssistantMessage.builder()
              .content(entity.getMessage())
              .properties(sourceMessageMetadata(entity.getId()))
              .build();
      case SYSTEM ->
          SystemMessage.builder()
              .text(entity.getMessage())
              .metadata(sourceMessageMetadata(entity.getId()))
              .build();
      default -> toUserMessage(entity);
    };
  }

  private Message toChatMessage(Role role, String text) {
    return switch (role) {
      case ASSISTANT -> new AssistantMessage(text);
      case SYSTEM -> new SystemMessage(text);
      default -> new UserMessage(text);
    };
  }

  private UserMessage toUserMessage(
      MessageEntity entity, List<Media> media, Entitlements entitlements) {
    return toUserMessage(entity.getMessage(), media, entitlements, entity.getId());
  }

  private UserMessage toUserMessage(MessageEntity entity) {
    return toUserMessage(entity.getMessage(), List.of(), null, entity.getId());
  }

  private UserMessage toUserMessage(
      String text, List<Media> media, Entitlements entitlements) {
    return toUserMessage(text, media, entitlements, null);
  }

  private UserMessage toUserMessage(
      String text,
      List<Media> media,
      Entitlements entitlements,
      Long sourceMessageId) {
    return UserMessage.builder()
        .text(text)
        .media(media == null ? List.of() : media)
        .metadata(sourceMessageMetadata(sourceMessageId))
        .build();
  }

  private Map<String, Object> sourceMessageMetadata(Long sourceMessageId) {
    return sourceMessageId == null ? Map.of() : Map.of("sourceMessageId", sourceMessageId);
  }

  private boolean hasDocumentContext(List<Document> retrievedDocuments) {
    return retrievedDocuments != null
        && retrievedDocuments.stream()
            .anyMatch(
                document ->
                    document != null
                        && document.getText() != null
                        && !document.getText().isBlank());
  }

  record PromptMessageRequest(
      UUID chatId,
      List<Media> media,
      Long targetUserMessageId,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      List<Document> retrievedDocuments) {}

  record StatelessPromptMessageRequest(
      List<StatelessChatMessage> history,
      String currentContent,
      List<Media> media,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      List<Document> retrievedDocuments) {}

  record StatelessChatMessage(Role role, String content) {}

  record PromptMessageBatch(
      List<Message> requestMessages,
      List<Message> historyMessages,
      List<Document> retrievedDocuments,
      int totalMessageCount) {

    private static PromptMessageBatch from(
        List<Message> messages, Long targetUserMessageId, List<Document> retrievedDocuments) {
      if (messages.size() <= 2) {
        return new PromptMessageBatch(
            List.copyOf(messages),
            List.of(),
            normalizedRetrievedDocuments(retrievedDocuments),
            messages.size());
      }
      List<Message> requestMessages =
          List.of(messages.getFirst(), messages.getLast());
      List<Message> historyMessages =
          List.copyOf(messages.subList(1, messages.size() - 1));
      return new PromptMessageBatch(
          requestMessages,
          historyMessages,
          normalizedRetrievedDocuments(retrievedDocuments),
          messages.size());
    }

    private static PromptMessageBatch combined(List<Message> messages) {
      return new PromptMessageBatch(List.copyOf(messages), List.of(), List.of(), messages.size());
    }

    private static boolean canUseAdvisors(List<Message> messages, Long targetUserMessageId) {
      return targetUserMessageId == null
          || (!messages.isEmpty() && isTargetUserMessageLast(messages, targetUserMessageId));
    }

    List<Message> combinedMessages() {
      if (historyMessages.isEmpty()) {
        return requestMessages;
      }
      List<Message> combined = new ArrayList<>(totalMessageCount);
      combined.add(requestMessages.getFirst());
      combined.addAll(historyMessages);
      combined.add(requestMessages.getLast());
      return combined;
    }

    boolean hasDocumentContext() {
      return !retrievedDocuments.isEmpty();
    }

    private static List<Document> normalizedRetrievedDocuments(List<Document> retrievedDocuments) {
      if (retrievedDocuments == null || retrievedDocuments.isEmpty()) {
        return List.of();
      }
      return List.copyOf(
          retrievedDocuments.stream()
              .filter(
                  document ->
                      document != null
                          && document.getText() != null
                          && !document.getText().isBlank())
              .toList());
    }

    private static boolean isTargetUserMessageLast(List<Message> messages, Long targetUserMessageId) {
      Message last = messages.getLast();
      if (!(last instanceof UserMessage userMessage)) {
        return false;
      }
      Object sourceMessageId = userMessage.getMetadata().get("sourceMessageId");
      return targetUserMessageId.equals(sourceMessageId);
    }
  }
}
