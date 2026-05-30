package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RunbookExportDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunbookExportService {

  private static final String DEFAULT_TITLE = "Untitled runbook";

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final Clock clock;

  public RunbookExportService(ChatRepository chatRepository, MessageRepository messageRepository) {
    this(chatRepository, messageRepository, Clock.systemUTC());
  }

  RunbookExportService(
      ChatRepository chatRepository, MessageRepository messageRepository, Clock clock) {
    this.chatRepository = chatRepository;
    this.messageRepository = messageRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RunbookExportDTO generateRunbook(UUID chatId, String userId) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));
    List<MessageEntity> messages = messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);
    String title = normalizeTitle(chat.getTitle());

    StringBuilder markdown = new StringBuilder();
    markdown.append("# ").append(title).append("\n\n");
    markdown
        .append("> Exported from Chat Sribiddi on ")
        .append(LocalDate.now(clock))
        .append("\n\n");
    markdown.append("## Summary\n\n");
    markdown
        .append("This runbook was generated from ")
        .append(messages.size())
        .append(" chat messages. Review the summary before publishing.\n\n");
    markdown.append("## Steps\n\n");

    if (messages.isEmpty()) {
      markdown.append("_No messages available._\n\n");
    } else {
      appendMessages(markdown, messages);
    }

    markdown.append("## Notes\n\n");
    markdown.append("- Verify commands, credentials, and environment-specific values before use.\n");
    return new RunbookExportDTO(filenameFor(title), markdown.toString());
  }

  private static void appendMessages(StringBuilder markdown, List<MessageEntity> messages) {
    int step = 1;
    for (MessageEntity message : messages) {
      Role role = message.getRole();
      if (role == Role.USER) {
        markdown.append("### Step ").append(step++).append("\n\n");
        markdown.append(content(message)).append("\n\n");
      } else if (role == Role.ASSISTANT) {
        markdown.append("#### Result\n\n");
        markdown.append(content(message)).append("\n\n");
      } else {
        markdown.append("#### Context\n\n");
        markdown.append(content(message)).append("\n\n");
      }
    }
  }

  private static String content(MessageEntity message) {
    String content = message == null ? null : message.getMessage();
    return content == null || content.isBlank() ? "_No content._" : content.trim();
  }

  private static String normalizeTitle(String title) {
    return title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
  }

  private static String filenameFor(String title) {
    String slug =
        title
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
    if (slug.isBlank()) {
      slug = "runbook";
    }
    return slug + ".md";
  }
}
