package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RepositoryBackedChatMemoryTest {

  @Mock private MessageRepository messageRepository;

  @Test
  void findByConversationId_loadsMessagesBeforeCurrentMessageAndReturnsOldestFirst() {
    UUID chatId = UUID.randomUUID();
    RepositoryBackedChatMemory memory = new RepositoryBackedChatMemory(messageRepository);
    MessageEntity newerAssistant = message(2L, Role.ASSISTANT, "newer response");
    MessageEntity olderUser = message(1L, Role.USER, "older request");
    when(messageRepository.findContextMessagesBefore(eq(chatId), eq(3L), any()))
        .thenReturn(List.of(newerAssistant, olderUser));

    var messages =
        memory.findByConversationId(RepositoryBackedChatMemory.conversationId(chatId, 3L, 4));

    assertThat(messages).hasSize(2);
    assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
    assertThat(messages.getFirst().getText()).isEqualTo("older request");
    assertThat(messages.getLast()).isInstanceOf(AssistantMessage.class);
    assertThat(messages.getLast().getText()).isEqualTo("newer response");

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(messageRepository).findContextMessagesBefore(eq(chatId), eq(3L), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(4);
  }

  @Test
  void findByConversationId_ignoresInvalidConversationId() {
    RepositoryBackedChatMemory memory = new RepositoryBackedChatMemory(messageRepository);

    assertThat(memory.findByConversationId("invalid")).isEmpty();

    verify(messageRepository, never()).findContextMessagesBefore(any(), any(), any());
  }

  @Test
  void chatMemory_wrapsRepositoryInSpringAiMessageWindowChatMemory() {
    var memory = RepositoryBackedChatMemory.chatMemory(messageRepository, 4);

    assertThat(memory).isInstanceOf(MessageWindowChatMemory.class);
  }

  private static MessageEntity message(Long id, Role role, String text) {
    MessageEntity entity = new MessageEntity();
    entity.setId(id);
    entity.setRole(role);
    entity.setMessage(text);
    return entity;
  }
}
