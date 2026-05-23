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
    builder =
        new PromptMessageBuilder(
            messageRepository, chatProperties, new MediaValidationService(chatProperties));
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
                "[document 1, chunk 1]\nImportant extracted text."));

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
