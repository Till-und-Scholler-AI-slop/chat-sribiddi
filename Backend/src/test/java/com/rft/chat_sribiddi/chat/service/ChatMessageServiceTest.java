package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.chat.dto.MessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.ToolCallLogResponseDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.chat.repository.ToolCallLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

  @Mock private MessageRepository messageRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private ToolCallLogRepository toolCallLogRepository;

  private ChatProperties chatProperties;
  private ChatMessageService chatMessageService;

  private UUID chatId;
  private String userId;

  @BeforeEach
  void setUp() {
    chatProperties = new ChatProperties();
    chatProperties.setMessagePageSize(50);
    chatProperties.setMaxChatTitleLength(100);
    chatMessageService =
        new ChatMessageService(messageRepository, chatRepository, toolCallLogRepository, chatProperties);
    chatId = UUID.randomUUID();
    userId = "user-123";
  }

  // ── findByIdAndChatId ───────────────────────────────────────────────────

  @Test
  void findByIdAndChatId_returnsMessageWhenFound() {
    MessageEntity message = new MessageEntity();
    message.setId(1L);
    message.setChatId(chatId);
    when(messageRepository.findByIdAndChatId(1L, chatId)).thenReturn(Optional.of(message));

    Optional<MessageEntity> result = chatMessageService.findByIdAndChatId(1L, chatId);

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo(1L);
    assertThat(result.get().getChatId()).isEqualTo(chatId);
  }

  // ── listMessages ────────────────────────────────────────────────────────

  @Test
  void listMessages_returnsMessagesWhenChatExists() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity msg1 = new MessageEntity();
    msg1.setId(1L);
    msg1.setChatId(chatId);
    MessageEntity msg2 = new MessageEntity();
    msg2.setId(2L);
    msg2.setChatId(chatId);
    List<MessageEntity> messages = List.of(msg2, msg1); // desc order from repo
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any(OffsetPageRequest.class)))
        .thenReturn(messages);

    List<MessageEntity> result = chatMessageService.listMessages(chatId, userId, null, null);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getId()).isEqualTo(1L); // reversed
    assertThat(result.get(1).getId()).isEqualTo(2L);
  }

  @Test
  void listMessages_throwsChatNotFoundWhenChatMissing() {
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> chatMessageService.listMessages(chatId, userId, null, null))
        .isInstanceOf(ChatNotFoundException.class)
        .hasMessageContaining(chatId.toString());
  }

  // ── listMessageResponses ────────────────────────────────────────────────

  @Test
  void listMessageResponses_returnsResponsesWithToolCallFlags() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    MessageEntity msg1 = new MessageEntity();
    msg1.setId(1L);
    msg1.setChatId(chatId);
    msg1.setRole(Role.USER);
    MessageEntity msg2 = new MessageEntity();
    msg2.setId(2L);
    msg2.setChatId(chatId);
    msg2.setRole(Role.ASSISTANT);
    List<MessageEntity> messages = List.of(msg2, msg1);
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any(OffsetPageRequest.class)))
        .thenReturn(messages);
    when(toolCallLogRepository.findMessageIdsWithToolCalls(List.of(1L, 2L)))
        .thenReturn(List.of(2L));

    List<MessageResponseDTO> result =
        chatMessageService.listMessageResponses(chatId, userId, null, null);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).id()).isEqualTo(1L);
    assertThat(result.get(0).hasToolCalls()).isFalse();
    assertThat(result.get(1).id()).isEqualTo(2L);
    assertThat(result.get(1).hasToolCalls()).isTrue();
  }

  @Test
  void listMessageResponses_returnsEmptyListWhenNoMessages() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(messageRepository.findByChatIdOrderByCreatedAtDesc(eq(chatId), any(OffsetPageRequest.class)))
        .thenReturn(List.of());

    List<MessageResponseDTO> result =
        chatMessageService.listMessageResponses(chatId, userId, null, null);

    assertThat(result).isEmpty();
  }

  // ── listToolCallLogs ────────────────────────────────────────────────────

  @Test
  void listToolCallLogs_returnsLogsWhenMessageAndChatExist() {
    MessageEntity message = new MessageEntity();
    message.setId(1L);
    message.setChatId(chatId);
    when(messageRepository.findById(1L)).thenReturn(Optional.of(message));

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));

    ToolCallLogEntity log = new ToolCallLogEntity();
    log.setId(10L);
    log.setMessageId(1L);
    log.setToolName("calculator");
    log.setInputPayload("{\"a\":1}");
    log.setOutputPayload("2");
    log.setStartedAt(Instant.now());
    log.setDurationMs(100L);
    when(toolCallLogRepository.findByMessageIdOrderByStartedAtAsc(1L)).thenReturn(List.of(log));

    List<ToolCallLogResponseDTO> result = chatMessageService.listToolCallLogs(1L, userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).toolName()).isEqualTo("calculator");
  }

  @Test
  void listToolCallLogs_throwsNotFoundWhenMessageMissing() {
    when(messageRepository.findById(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> chatMessageService.listToolCallLogs(1L, userId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Message not found: 1");
  }

  @Test
  void listToolCallLogs_throwsNotFoundWhenChatMissing() {
    MessageEntity message = new MessageEntity();
    message.setId(1L);
    message.setChatId(chatId);
    when(messageRepository.findById(1L)).thenReturn(Optional.of(message));
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> chatMessageService.listToolCallLogs(1L, userId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Message not found: 1");
  }

  // ── deleteMessagesByChatId ──────────────────────────────────────────────

  @Test
  void deleteMessagesByChatId_delegatesToRepository() {
    chatMessageService.deleteMessagesByChatId(chatId);
    verify(messageRepository).deleteByChatId(chatId);
  }

  // ── deleteMessagesAfter ─────────────────────────────────────────────────

  @Test
  void deleteMessagesAfter_delegatesToRepository() {
    chatMessageService.deleteMessagesAfter(chatId, 5L);
    verify(messageRepository).deleteByIdGreaterThanAndChatId(chatId, 5L);
  }

  // ── updateMessage ───────────────────────────────────────────────────────

  @Test
  void updateMessage_delegatesToRepository() {
    MessageEntity message = new MessageEntity();
    message.setId(1L);
    chatMessageService.updateMessage(message);
    verify(messageRepository).save(message);
  }

  // ── persistUserMessage ──────────────────────────────────────────────────

  @Test
  void persistUserMessage_savesAndReturnsMessage() {
    MessageEntity saved = new MessageEntity();
    saved.setId(1L);
    saved.setChatId(chatId);
    saved.setMessage("hello");
    saved.setRole(Role.USER);
    saved.setContext("ctx");
    when(messageRepository.save(any(MessageEntity.class))).thenReturn(saved);

    MessageEntity result = chatMessageService.persistUserMessage(chatId, "hello", "ctx");

    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getRole()).isEqualTo(Role.USER);
    assertThat(result.getMessage()).isEqualTo("hello");
    assertThat(result.getContext()).isEqualTo("ctx");
  }

  @Test
  void persistUserMessage_fkViolation_throwsChatNotFound() {
    when(messageRepository.save(any(MessageEntity.class))).thenThrow(chatFkViolation());

    assertThatThrownBy(() -> chatMessageService.persistUserMessage(chatId, "hello", null))
        .isInstanceOf(ChatNotFoundException.class)
        .hasMessageContaining(chatId.toString());
  }

  // ── persistAssistantMessage ─────────────────────────────────────────────

  @Test
  void persistAssistantMessage_savesAndReturnsMessage() {
    MessageEntity saved = new MessageEntity();
    saved.setId(2L);
    saved.setChatId(chatId);
    saved.setMessage("hi there");
    saved.setRole(Role.ASSISTANT);
    saved.setPromptTokens(10L);
    saved.setGenerationTokens(5L);
    saved.setTotalTokens(15L);
    when(messageRepository.save(any(MessageEntity.class))).thenReturn(saved);

    MessageEntity result =
        chatMessageService.persistAssistantMessage(chatId, "hi there", null, 10L, 5L, 15L);

    assertThat(result.getId()).isEqualTo(2L);
    assertThat(result.getRole()).isEqualTo(Role.ASSISTANT);
    assertThat(result.getMessage()).isEqualTo("hi there");
    assertThat(result.getPromptTokens()).isEqualTo(10L);
    assertThat(result.getGenerationTokens()).isEqualTo(5L);
    assertThat(result.getTotalTokens()).isEqualTo(15L);
  }

  @Test
  void persistAssistantMessage_fkViolation_throwsChatNotFound() {
    when(messageRepository.save(any(MessageEntity.class))).thenThrow(chatFkViolation());

    assertThatThrownBy(() -> chatMessageService.persistAssistantMessage(chatId, "hi", null, 0L, 0L, 0L))
        .isInstanceOf(ChatNotFoundException.class)
        .hasMessageContaining(chatId.toString());
  }

  // ── saveMessageOrThrowIfChatMissing ─────────────────────────────────────

  @Test
  void saveMessageOrThrowIfChatMissing_returnsSavedMessage() {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    MessageEntity saved = new MessageEntity();
    saved.setId(1L);
    when(messageRepository.save(entity)).thenReturn(saved);

    MessageEntity result = chatMessageService.saveMessageOrThrowIfChatMissing(entity);

    assertThat(result.getId()).isEqualTo(1L);
  }

  @Test
  void saveMessageOrThrowIfChatMissing_chatFkViolation_throwsChatNotFound() {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    when(messageRepository.save(entity)).thenThrow(chatFkViolation());

    assertThatThrownBy(() -> chatMessageService.saveMessageOrThrowIfChatMissing(entity))
        .isInstanceOf(ChatNotFoundException.class)
        .hasMessageContaining(chatId.toString());
  }

  @Test
  void saveMessageOrThrowIfChatMissing_otherFkViolation_rethrown() {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    when(messageRepository.save(entity))
        .thenThrow(new DataIntegrityViolationException("some other constraint"));

    assertThatThrownBy(() -> chatMessageService.saveMessageOrThrowIfChatMissing(entity))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("some other constraint");
  }

  // ── normalizeChatTitle ──────────────────────────────────────────────────

  @Test
  void normalizeChatTitle_nullReturnsNull() {
    assertThat(chatMessageService.normalizeChatTitle(null)).isNull();
  }

  @Test
  void normalizeChatTitle_blankReturnsNull() {
    assertThat(chatMessageService.normalizeChatTitle("   ")).isNull();
  }

  @Test
  void normalizeChatTitle_withinLimitReturnsTrimmed() {
    assertThat(chatMessageService.normalizeChatTitle("  Hello World  ")).isEqualTo("Hello World");
  }

  @Test
  void normalizeChatTitle_exceedsLimitTruncates() {
    String longTitle = "A".repeat(150);
    assertThat(chatMessageService.normalizeChatTitle(longTitle))
        .hasSize(100)
        .isEqualTo("A".repeat(100));
  }

  // ── autoTitleChatIfNeeded ───────────────────────────────────────────────

  @Test
  void autoTitleChatIfNeeded_alreadyTitled_returnsNull() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle("Existing Title");

    String result = chatMessageService.autoTitleChatIfNeeded(chat, "New message");

    assertThat(result).isNull();
    verify(chatRepository, org.mockito.Mockito.never()).setTitleIfBlank(any(), any());
  }

  @Test
  void autoTitleChatIfNeeded_untitled_setsTitleAndReturnsIt() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle(null);
    when(chatRepository.setTitleIfBlank(chatId, "New message")).thenReturn(1);

    String result = chatMessageService.autoTitleChatIfNeeded(chat, "New message");

    assertThat(result).isEqualTo("New message");
    assertThat(chat.getTitle()).isEqualTo("New message");
  }

  @Test
  void autoTitleChatIfNeeded_concurrentTitleAlreadySet_returnsNull() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle(null);
    when(chatRepository.setTitleIfBlank(chatId, "New message")).thenReturn(0);

    String result = chatMessageService.autoTitleChatIfNeeded(chat, "New message");

    assertThat(result).isNull();
  }

  @Test
  void autoTitleChatIfNeeded_blankMessageContent_returnsNull() {
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle(null);

    String result = chatMessageService.autoTitleChatIfNeeded(chat, "   ");

    assertThat(result).isNull();
    verify(chatRepository, org.mockito.Mockito.never()).setTitleIfBlank(any(), any());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static DataIntegrityViolationException chatFkViolation() {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new RuntimeException(
            "ERROR: insert or update on table \"messages\" violates foreign key constraint \"messages_chat_id_fkey\""));
  }
}
