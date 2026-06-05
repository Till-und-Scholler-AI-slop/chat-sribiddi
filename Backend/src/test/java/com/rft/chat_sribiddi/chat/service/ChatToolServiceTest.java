package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.AccessRestrictionException;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.ToolProperties;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatToolServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private ChatToolRepository chatToolRepository;
  @Mock private ToolRegistry toolRegistry;
  @Mock private ApiKeyService apiKeyService;
  @Mock private CredentialAccessService credentialAccessService;
  @Mock private ChatTool tool;

  private ChatToolService service;
  private UUID chatId;
  private String userId;

  @BeforeEach
  void setUp() {
    service =
        new ChatToolService(
            chatRepository,
            chatToolRepository,
            toolRegistry,
            apiKeyService,
            credentialAccessService,
            new ToolProperties());
    chatId = UUID.randomUUID();
    userId = "user-123";

    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    lenient().when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    lenient().when(tool.getName()).thenReturn("url-fetcher");
    lenient().when(tool.getDisplayName()).thenReturn("URL Fetcher");
    lenient().when(tool.getDescription()).thenReturn("Fetches URLs");
    lenient().when(toolRegistry.getAll()).thenReturn(List.of(tool));
    lenient().when(chatToolRepository.findByChatId(chatId)).thenReturn(List.of());
    lenient().when(toolRegistry.has("url-fetcher")).thenReturn(true);
  }

  @Test
  void setToolEnabled_rejectsToolOutsideAccessLimits() {
    when(credentialAccessService.authenticatedLimits())
        .thenReturn(new AccessLimits(5, 1, 1, 20, Set.of("calculator"), 1));

    assertThatThrownBy(
            () -> service.setToolEnabled(chatId, userId, new SetChatToolRequestDTO("url-fetcher", true)))
        .isInstanceOfSatisfying(
            AccessRestrictionException.class,
            ex -> assertThat(ex.getReason()).isEqualTo(AccessRestrictionException.Reason.TOOL_NOT_ALLOWED));

    verify(chatToolRepository, never()).save(any());
  }

  @Test
  void listAvailableTools_marksToolUnavailableWhenAccessLimitsDoNotAllowIt() {
    when(credentialAccessService.authenticatedLimits())
        .thenReturn(new AccessLimits(5, 1, 1, 20, Set.of("calculator"), 1));

    var tools = service.listAvailableTools(chatId, userId);

    assertThat(tools).hasSize(1);
    assertThat(tools.getFirst().enabled()).isFalse();
  }

  @Test
  void listAvailableTools_marksBraveSearchEnabledOnlyWithUserKey() {
    when(tool.getName()).thenReturn("brave-search");
    when(tool.requiresUserConfig()).thenReturn(true);
    when(credentialAccessService.authenticatedLimits())
        .thenReturn(new AccessLimits(5, 1, 1, 20, Set.of("*"), 1));
    when(apiKeyService.getKey(userId, RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));

    var tools = service.listAvailableTools(chatId, userId);

    assertThat(tools).hasSize(1);
    assertThat(tools.getFirst().enabled()).isTrue();
  }

  @Test
  void setToolEnabled_allowsBraveSearchWhenUserKeyExists() {
    when(toolRegistry.has("brave-search")).thenReturn(true);
    when(credentialAccessService.authenticatedLimits())
        .thenReturn(new AccessLimits(5, 1, 1, 20, Set.of("calculator"), 1));
    when(apiKeyService.getKey(userId, RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));
    when(chatToolRepository.findByChatIdAndToolName(chatId, "brave-search"))
        .thenReturn(Optional.empty());
    when(chatToolRepository.save(any(ChatToolEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.setToolEnabled(chatId, userId, new SetChatToolRequestDTO("brave-search", true));

    verify(chatToolRepository).save(any());
  }
}
