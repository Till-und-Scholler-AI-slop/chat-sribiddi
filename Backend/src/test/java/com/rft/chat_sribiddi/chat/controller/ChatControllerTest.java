package com.rft.chat_sribiddi.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.chat.dto.RunbookExportDTO;
import com.rft.chat_sribiddi.chat.service.ChatDocumentService;
import com.rft.chat_sribiddi.chat.service.ChatService;
import com.rft.chat_sribiddi.chat.service.GatewayChatService;
import com.rft.chat_sribiddi.chat.service.RunbookExportService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

  @Mock private ChatService chatService;
  @Mock private ChatDocumentService chatDocumentService;
  @Mock private GatewayChatService gatewayChatService;
  @Mock private RunbookExportService runbookExportService;
  @Mock private OidcUser principal;

  @Test
  void exportRunbook_delegatesToRunbookExportServiceForAuthenticatedUser() {
    UUID chatId = UUID.randomUUID();
    RunbookExportDTO export = new RunbookExportDTO("incident.md", "# Incident");
    ChatController controller =
        new ChatController(chatService, chatDocumentService, gatewayChatService, runbookExportService);
    when(principal.getSubject()).thenReturn("user-123");
    when(runbookExportService.generateRunbook(chatId, "user-123")).thenReturn(export);

    var response = controller.exportRunbook(principal, chatId);

    assertThat(response.getBody()).isEqualTo(export);
    verify(runbookExportService).generateRunbook(chatId, "user-123");
  }
}
