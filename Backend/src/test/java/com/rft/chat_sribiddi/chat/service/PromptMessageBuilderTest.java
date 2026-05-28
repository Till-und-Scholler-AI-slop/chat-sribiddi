package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Pageable;

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
  void build_limitsContextByEntitlements() {
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of());

    builder.build(
        new PromptMessageRequest(
            chatId,
            List.of(),
            null,
            null,
            RequestDTO.Provider.OPENAI,
            "gpt-5.2",
            entitlements(3),
            null));

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findByChatIdOrderByCreatedAtDesc(eq(chatId), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
  }

  @Test
  void build_ordersHistoryOldestFirstAfterInstructions() {
    MessageEntity newerAssistant = message(2L, Role.ASSISTANT, "newer response");
    MessageEntity olderUser = message(1L, Role.USER, "older request");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(newerAssistant, olderUser));

    List<Message> messages =
        builder.build(
            new PromptMessageRequest(
                chatId,
                List.of(),
                null,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                null));

    assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) messages.get(1)).getText()).isEqualTo("older request");
    assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
    assertThat(((AssistantMessage) messages.get(2)).getText()).isEqualTo("newer response");
  }

  @Test
  void buildBatch_splitsHistoryForSpringAiAdvisorWhenTargetUserMessageIsLatest() {
    MessageEntity currentUser = message(3L, Role.USER, "current request");
    MessageEntity previousAssistant = message(2L, Role.ASSISTANT, "previous response");
    MessageEntity previousUser = message(1L, Role.USER, "previous request");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(currentUser, previousAssistant, previousUser));

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                null));

    assertThat(batch.requestMessages()).hasSize(2);
    assertThat(batch.historyMessages()).hasSize(2);
    assertThat(batch.retrievedDocuments()).isEmpty();
    assertThat(((UserMessage) batch.requestMessages().getLast()).getText())
        .isEqualTo("current request");
    assertThat(((UserMessage) batch.historyMessages().getFirst()).getText())
        .isEqualTo("previous request");
    assertThat(((AssistantMessage) batch.historyMessages().getLast()).getText())
        .isEqualTo("previous response");
  }

  @Test
  void buildBatch_keepsCombinedPromptWhenTargetUserMessageIsNotLatest() {
    MessageEntity concurrentNewerUser = message(4L, Role.USER, "newer request");
    MessageEntity currentUser = message(3L, Role.USER, "current request");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(concurrentNewerUser, currentUser));

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                null));

    assertThat(batch.historyMessages()).isEmpty();
    assertThat(batch.requestMessages()).hasSize(3);
    assertThat(((UserMessage) batch.requestMessages().get(1)).getText()).isEqualTo("current request");
    assertThat(((UserMessage) batch.requestMessages().get(2)).getText()).isEqualTo("newer request");
  }

  @Test
  void buildBatch_keepsDocumentContextForSpringAiAdvisorWhenTargetUserMessageIsLatest() {
    MessageEntity currentUser = message(3L, Role.USER, "current request");
    MessageEntity previousAssistant = message(2L, Role.ASSISTANT, "previous response");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(currentUser, previousAssistant));

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                documents("Important extracted text.")));

    assertThat(batch.hasDocumentContext()).isTrue();
    assertThat(batch.retrievedDocuments()).extracting(Document::getText)
        .containsExactly("Important extracted text.");
    assertThat(((UserMessage) batch.requestMessages().getLast()).getText())
        .isEqualTo("current request");
    assertThat(batch.combinedMessages())
        .extracting(message -> message instanceof UserMessage user ? user.getText() : message.getText())
        .doesNotContain("retrieved document excerpts");
  }

  @Test
  void buildBatch_inlinesDocumentContextWhenTargetUserMessageIsNotLatest() {
    MessageEntity concurrentNewerUser = message(4L, Role.USER, "newer request");
    MessageEntity currentUser = message(3L, Role.USER, "current request");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(concurrentNewerUser, currentUser));

    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildBatch(
            new PromptMessageRequest(
                chatId,
                List.of(),
                3L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                documents("Important extracted text.")));

    assertThat(batch.historyMessages()).isEmpty();
    assertThat(batch.hasDocumentContext()).isFalse();
    assertThat(batch.requestMessages()).hasSize(3);
    assertThat(((UserMessage) batch.requestMessages().get(1)).getText())
        .contains("retrieved document excerpts")
        .contains("Important extracted text")
        .contains("current request");
    assertThat(((UserMessage) batch.requestMessages().get(2)).getText()).isEqualTo("newer request");
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
  void buildStatelessBatch_keepsDocumentContextForSpringAiAdvisor() {
    PromptMessageBuilder.PromptMessageBatch batch =
        builder.buildStatelessBatch(
            new PromptMessageBuilder.StatelessPromptMessageRequest(
                List.of(),
                "current request",
                List.of(),
                null,
                RequestDTO.Provider.OPENROUTER,
                "openrouter/free",
                entitlements(20),
                documents("Important extracted text.")));

    assertThat(batch.hasDocumentContext()).isTrue();
    assertThat(((UserMessage) batch.requestMessages().getLast()).getText())
        .isEqualTo("current request");
    assertThat(batch.combinedMessages())
        .extracting(message -> message instanceof UserMessage user ? user.getText() : message.getText())
        .doesNotContain("retrieved document excerpts");
  }

  @Test
  void build_usesUserInstructionMessageForGoogleGemmaModels() {
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of());

    List<Message> messages =
        builder.build(
            new PromptMessageRequest(
                chatId,
                List.of(),
                null,
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
        .isEqualTo("Contains last 1 messages (instructions sent as user message)");
  }

  @Test
  void build_injectsDocumentContextIntoTargetUserMessage() {
    MessageEntity userMessage = message(10L, Role.USER, "What is in this file?");
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any()))
        .thenReturn(List.of(userMessage));

    List<Message> messages =
        builder.build(
            new PromptMessageRequest(
                chatId,
                List.of(),
                10L,
                null,
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                entitlements(20),
                documents("Important extracted text.")));

    assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) messages.get(1)).getText())
        .contains("retrieved document excerpts")
        .contains("Important extracted text")
        .contains("User request:")
        .contains("What is in this file?");
  }

  @Test
  void supportsSystemInstruction_onlyDisablesGoogleGemmaModels() {
    assertThat(PromptMessageBuilder.supportsSystemInstruction(
            RequestDTO.Provider.GOOGLE, "gemma-3-27b-it"))
        .isFalse();
    assertThat(PromptMessageBuilder.supportsSystemInstruction(
            RequestDTO.Provider.GOOGLE, "gemini-2.5-flash"))
        .isTrue();
    assertThat(PromptMessageBuilder.supportsSystemInstruction(
            RequestDTO.Provider.OPENROUTER, "google/gemma-3-27b-it"))
        .isTrue();
  }

  private static MessageEntity message(Long id, Role role, String text) {
    MessageEntity entity = new MessageEntity();
    entity.setId(id);
    entity.setRole(role);
    entity.setMessage(text);
    return entity;
  }

  private static List<Document> documents(String text) {
    return List.of(
        new Document(
            text,
            Map.of(
                "document_id", 1L,
                "chunk_index", 0,
                "page_start", 2,
                "page_end", 3)));
  }

  private static Entitlements entitlements(int contextMessageLimit) {
    return new Entitlements(
        Plan.FREE,
        5,
        5_242_880,
        20_971_520,
        contextMessageLimit,
        Set.of("*"),
        100,
        1,
        null,
        null);
  }
}
