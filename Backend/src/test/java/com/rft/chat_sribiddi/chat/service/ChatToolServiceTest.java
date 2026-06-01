package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.SetChatToolRequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.connectors.ConnectorAccountService;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.tools.ToolProperties;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialResolver;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
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
  @Mock private EntitlementService entitlementService;
  @Mock private ToolCredentialResolver toolCredentialResolver;
  @Mock private ConnectorAccountService connectorAccountService;
  @Mock private ChatTool tool;

  private ChatToolService service;
  private WebSearchProperties webSearchProperties;
  private UUID chatId;
  private String userId;

  @BeforeEach
  void setUp() {
    webSearchProperties = new WebSearchProperties();
    service =
        new ChatToolService(
            chatRepository,
            chatToolRepository,
            toolRegistry,
            apiKeyService,
            entitlementService,
            webSearchProperties,
            new ToolProperties(),
            toolCredentialResolver,
            connectorAccountService);
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
  void setToolEnabled_rejectsToolOutsidePlan() {
    when(entitlementService.get(userId)).thenReturn(entitlements(Plan.FREE, Set.of("calculator")));

    assertThatThrownBy(
            () -> service.setToolEnabled(chatId, userId, new SetChatToolRequestDTO("url-fetcher", true)))
        .isInstanceOfSatisfying(
            PlanRestrictionException.class,
            ex ->
                assertThat(ex.getReason())
                    .isEqualTo(PlanRestrictionException.Reason.TOOL_NOT_ALLOWED));

    verify(chatToolRepository, never()).save(any());
  }

  @Test
  void listAvailableTools_marksToolUnavailableWhenPlanDoesNotAllowIt() {
    when(entitlementService.get(userId)).thenReturn(entitlements(Plan.FREE, Set.of("calculator")));

    var tools = service.listAvailableTools(chatId, userId);

    assertThat(tools).hasSize(1);
    assertThat(tools.getFirst().enabled()).isFalse();
  }

  @Test
  void listAvailableTools_marksBraveSearchEnabledWhenServerKeyExists() {
    when(tool.getName()).thenReturn("brave-search");
    when(tool.requiresUserConfig()).thenReturn(true);
    when(entitlementService.get(userId))
        .thenReturn(entitlements(Plan.PREMIUM, Set.of("brave-search")));
    when(apiKeyService.getKey(userId, RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.empty());
    webSearchProperties.setApiKey("server-brave-key");

    var tools = service.listAvailableTools(chatId, userId);

    assertThat(tools).hasSize(1);
    assertThat(tools.getFirst().enabled()).isTrue();
  }

  @Test
  void listAvailableTools_marksBraveSearchEnabledWhenUserKeyExistsEvenIfPlanDeniesTool() {
    when(tool.getName()).thenReturn("brave-search");
    when(tool.requiresUserConfig()).thenReturn(true);
    when(entitlementService.get(userId)).thenReturn(entitlements(Plan.FREE, Set.of("calculator")));
    when(apiKeyService.getKey(userId, RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));

    var tools = service.listAvailableTools(chatId, userId);

    assertThat(tools).hasSize(1);
    assertThat(tools.getFirst().enabled()).isTrue();
  }

  @Test
  void setToolEnabled_allowsBraveSearchWhenUserKeyExistsEvenIfPlanDeniesTool() {
    when(toolRegistry.has("brave-search")).thenReturn(true);
    when(entitlementService.get(userId)).thenReturn(entitlements(Plan.FREE, Set.of("calculator")));
    when(apiKeyService.getKey(userId, RequestDTO.Provider.BRAVE_SEARCH))
        .thenReturn(Optional.of("user-brave-key"));
    when(chatToolRepository.findByChatIdAndToolName(chatId, "brave-search"))
        .thenReturn(Optional.empty());
    when(chatToolRepository.save(any(ChatToolEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.setToolEnabled(chatId, userId, new SetChatToolRequestDTO("brave-search", true));

    verify(chatToolRepository).save(any());
  }

  private static Entitlements entitlements(Plan plan, Set<String> tools) {
    return new Entitlements(plan, 5, 5_242_880, 20_971_520, 20, tools, 100, 1, null, null);
  }
}
