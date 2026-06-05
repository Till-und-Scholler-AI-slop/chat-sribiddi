package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

@ExtendWith(MockitoExtension.class)
class PromptMessageBuilderTest {

  @Mock private MessageRepository messageRepository;

  private ChatProperties chatProperties;
  private PromptMessageBuilder builder;
  private UUID chatId;

  @BeforeEach
  void setUp() {
    chatProperties = new ChatProperties();
    builder = new PromptMessageBuilder(messageRepository, chatProperties);
    chatId = UUID.randomUUID();
  }

  @Test
  void build_createsPromptFromSystemInstructionAndCurrentUserMessage() {
    List<Message> messages =
        builder.build(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                10L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                null));

    assertThat(messages).hasSize(2);
    assertThat(messages.getFirst().getText()).contains("helpful assistant");
    assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
    UserMessage current = (UserMessage) messages.getLast();
    assertThat(current.getText()).isEqualTo("current request");
    assertThat(current.getMetadata()).containsEntry("sourceMessageId", 10L);
  }

  @Test
  void buildBatch_usesRepositoryBackedSpringAiChatMemoryForPersistentHistory() {
    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(5),
                null));

    assertThat(batch.requestMessages()).hasSize(2);
    assertThat(batch.historyMessages()).isEmpty();
    assertThat(batch.chatMemory()).isInstanceOf(MessageWindowChatMemory.class);
    assertThat(batch.conversationId()).isEqualTo(
        RepositoryBackedChatMemory.conversationId(chatId, 3L, 4));
    assertThat(((UserMessage) batch.requestMessages().getLast()).getText())
        .isEqualTo("current request");
  }

  @Test
  void buildBatch_reportsActualHistoryCountCappedByMemoryLimit() {
    // entitlements(5) -> context limit 5 -> memory window holds up to 4 prior messages.
    when(messageRepository.countContextMessagesBefore(chatId, 3L)).thenReturn(10L);

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(5),
                null));

    // 2 request messages (system + current) + min(10 prior, 4 window) = 6.
    assertThat(batch.totalMessageCount()).isEqualTo(6);
  }

  @Test
  void buildBatch_reportsActualHistoryCountWhenBelowMemoryLimit() {
    when(messageRepository.countContextMessagesBefore(chatId, 3L)).thenReturn(1L);

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                null));

    // 2 request messages + min(1 prior, 19 window) = 3 (no longer the inflated limit+1).
    assertThat(batch.totalMessageCount()).isEqualTo(3);
  }

  @Test
  void buildBatch_keepsDocumentRetrievalContextForSpringAiRagAdvisor() {
    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                documentContext()));

    assertThat(batch.hasDocumentContext()).isTrue();
    assertThat(batch.documentRetrievalContext())
        .isEqualTo(new PromptMessageBuilder.DocumentRetrievalContext(chatId, "user-1"));
    assertThat(batch.combinedMessages())
        .extracting(message -> message instanceof UserMessage user ? user.getText() : message.getText())
        .doesNotContain("retrieved document excerpts");
  }

  @Test
  void buildStatelessBatch_splitsBrowserHistoryForSpringAiAdvisor() {
    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildStatelessBatch(
            new PromptMessageBuilder.StatelessPromptMessageRequest(
                List.of(
                    new PromptMessageBuilder.StatelessChatMessage(Role.USER, "previous request"),
                    new PromptMessageBuilder.StatelessChatMessage(
                        Role.ASSISTANT, "previous response")),
                "current request",
                List.of(),
                null,
                RequestDTO.Provider.OPENROUTER,
                "openrouter/free",
                entitlements(20),
                null));

    assertThat(batch.requestMessages()).hasSize(2);
    assertThat(batch.historyMessages()).hasSize(2);
    assertThat(((UserMessage) batch.requestMessages().getLast()).getText())
        .isEqualTo("current request");
    assertThat(((UserMessage) batch.historyMessages().getFirst()).getText())
        .isEqualTo("previous request");
    assertThat(((AssistantMessage) batch.historyMessages().getLast()).getText())
        .isEqualTo("previous response");
  }

  @Test
  void build_usesUserInstructionMessageForGoogleGemmaModels() {
    List<Message> messages =
        builder.build(
            new PromptMessageRequest(
                chatId,
                "current request",
                List.of(),
                10L,
                "Use concise answers.",
                RequestDTO.Provider.GOOGLE,
                "gemma-3-27b-it",
                entitlements(20),
                null));

    assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) messages.getFirst()).getText())
        .contains("System instructions for this conversation")
        .contains("Use concise answers.");
    assertThat(
            builder.assistantContext(
                messages.size(), RequestDTO.Provider.GOOGLE, "gemma-3-27b-it"))
        .isEqualTo("Contains last 2 messages (instructions sent as user message)");
  }

  private PromptMessageBuilder.DocumentRetrievalContext documentContext() {
    return new PromptMessageBuilder.DocumentRetrievalContext(chatId, "user-1");
  }

  private static AccessLimits entitlements(int contextMessageLimit) {
    return new AccessLimits(
        5,
        5_242_880,
        20_971_520,
        contextMessageLimit,
        Set.of("*"),
        1);
  }
}
