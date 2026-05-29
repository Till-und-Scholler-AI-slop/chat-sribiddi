package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
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
    return requestMessages(request);
  }

  PromptMessageBatch buildBatch(PromptMessageRequest request) {
    List<Message> requestMessages = requestMessages(request);
    int memoryLimit = memoryMessageLimit(request.entitlements());
    if (memoryLimit <= 0) {
      // Context limit leaves no room for prior history beyond the current message.
      return PromptMessageBatch.withSpringAiMemory(
          requestMessages, null, null, 0, request.documentRetrievalContext());
    }
    long priorMessageCount =
        messageRepository.countContextMessagesBefore(
            request.chatId(), request.targetUserMessageId());
    int historyMessageCount = (int) Math.min(priorMessageCount, memoryLimit);
    String conversationId =
        RepositoryBackedChatMemory.conversationId(
            request.chatId(), request.targetUserMessageId(), memoryLimit);
    return PromptMessageBatch.withSpringAiMemory(
        requestMessages,
        RepositoryBackedChatMemory.chatMemory(messageRepository, memoryLimit),
        conversationId,
        historyMessageCount,
        request.documentRetrievalContext());
  }

  private List<Message> requestMessages(PromptMessageRequest request) {
    List<Message> messages = new ArrayList<>();
    String instructionContent = instructionContent(request.customInstructions());
    if (supportsSystemInstruction(request.provider(), request.modelId())) {
      messages.add(new SystemMessage(instructionContent));
    } else {
      messages.add(new UserMessage(USER_INSTRUCTION_PREFIX + instructionContent));
    }

    messages.add(
        toUserMessage(
            request.currentContent(),
            request.media(),
            request.entitlements(),
            request.targetUserMessageId()));
    return messages;
  }

  List<Message> buildStateless(StatelessPromptMessageRequest request) {
    return buildStatelessMessages(request);
  }

  PromptMessageBatch buildStatelessBatch(StatelessPromptMessageRequest request) {
    List<Message> advisorMessages = buildStatelessMessages(request);
    return PromptMessageBatch.from(advisorMessages, null, null);
  }

  private List<Message> buildStatelessMessages(StatelessPromptMessageRequest request) {
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

    int limit = totalContextMessageLimit(request.entitlements());
    int from = Math.max(0, allMessages.size() - limit);
    List<StatelessChatMessage> limited = allMessages.subList(from, allMessages.size());
    for (int i = 0; i < limited.size(); i++) {
      StatelessChatMessage item = limited.get(i);
      boolean isCurrent = i == limited.size() - 1;
      if (isCurrent
          && item.role() == Role.USER
          && !request.media().isEmpty()) {
        messages.add(
            toUserMessage(
                item.content(),
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

  private int totalContextMessageLimit(Entitlements entitlements) {
    int configured = chatProperties.getContextMessageLimit();
    if (entitlements == null || entitlements.contextMessageLimit() <= 0) {
      return Math.max(1, configured);
    }
    return Math.max(1, Math.min(configured, entitlements.contextMessageLimit()));
  }

  private int memoryMessageLimit(Entitlements entitlements) {
    return Math.max(0, totalContextMessageLimit(entitlements) - 1);
  }

  private Message toChatMessage(Role role, String text) {
    return switch (role) {
      case ASSISTANT -> new AssistantMessage(text);
      case SYSTEM -> new SystemMessage(text);
      default -> new UserMessage(text);
    };
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

  record PromptMessageRequest(
      UUID chatId,
      String currentContent,
      List<Media> media,
      Long targetUserMessageId,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      DocumentRetrievalContext documentRetrievalContext) {}

  record StatelessPromptMessageRequest(
      List<StatelessChatMessage> history,
      String currentContent,
      List<Media> media,
      String customInstructions,
      RequestDTO.Provider provider,
      String modelId,
      Entitlements entitlements,
      DocumentRetrievalContext documentRetrievalContext) {}

  record StatelessChatMessage(Role role, String content) {}

  record DocumentRetrievalContext(UUID chatId, String userId) {}

  record PromptMessageBatch(
      List<Message> requestMessages,
      List<Message> historyMessages,
      org.springframework.ai.chat.memory.ChatMemory chatMemory,
      String conversationId,
      DocumentRetrievalContext documentRetrievalContext,
      int totalMessageCount) {

    private static PromptMessageBatch from(
        List<Message> messages,
        Long targetUserMessageId,
        DocumentRetrievalContext documentRetrievalContext) {
      if (messages.size() <= 2) {
        return new PromptMessageBatch(
            List.copyOf(messages),
            List.of(),
            null,
            null,
            documentRetrievalContext,
            messages.size());
      }
      List<Message> requestMessages =
          List.of(messages.getFirst(), messages.getLast());
      List<Message> historyMessages =
          List.copyOf(messages.subList(1, messages.size() - 1));
      return new PromptMessageBatch(
          requestMessages,
          historyMessages,
          null,
          null,
          documentRetrievalContext,
          messages.size());
    }

    private static PromptMessageBatch withSpringAiMemory(
        List<Message> requestMessages,
        org.springframework.ai.chat.memory.ChatMemory chatMemory,
        String conversationId,
        int historyMessageCount,
        DocumentRetrievalContext documentRetrievalContext) {
      return new PromptMessageBatch(
          List.copyOf(requestMessages),
          List.of(),
          chatMemory,
          conversationId,
          documentRetrievalContext,
          requestMessages.size() + Math.max(0, historyMessageCount));
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
      return documentRetrievalContext != null
          && documentRetrievalContext.chatId() != null
          && documentRetrievalContext.userId() != null
          && !documentRetrievalContext.userId().isBlank();
    }
  }
}
