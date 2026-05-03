package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunbookExportServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;

  private RunbookExportService service;
  private UUID chatId;

  @BeforeEach
  void setUp() {
    service =
        new RunbookExportService(
            chatRepository,
            messageRepository,
            Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC));
    chatId = UUID.randomUUID();
  }

  @Test
  void generateRunbook_returnsStructuredMarkdownForOwnedChat() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId("user-123");
    chat.setTitle("API Incident");
    when(chatRepository.findByChatIdAndUserId(chatId, "user-123")).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtAsc(chatId))
        .thenReturn(
            List.of(
                message(Role.USER, "How do I restart the worker?"),
                message(Role.ASSISTANT, "Run `systemctl restart worker`.")));

    var result = service.generateRunbook(chatId, "user-123");

    assertThat(result.filename()).isEqualTo("api-incident.md");
    assertThat(result.markdown()).startsWith("# API Incident");
    assertThat(result.markdown()).contains("Exported from Chat Sribiddi on 2026-05-03");
    assertThat(result.markdown()).contains("## Summary");
    assertThat(result.markdown()).contains("### Step 1");
    assertThat(result.markdown()).contains("How do I restart the worker?");
    assertThat(result.markdown()).contains("#### Result");
    assertThat(result.markdown()).contains("Run `systemctl restart worker`.");
  }

  @Test
  void generateRunbook_rejectsChatsOwnedByAnotherUser() {
    when(chatRepository.findByChatIdAndUserId(chatId, "user-123")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.generateRunbook(chatId, "user-123"))
        .isInstanceOf(ChatNotFoundException.class);
    verify(messageRepository, never()).findByChatIdOrderByCreatedAtAsc(chatId);
  }

  private static MessageEntity message(Role role, String content) {
    MessageEntity message = new MessageEntity();
    message.setRole(role);
    message.setMessage(content);
    return message;
  }
}
