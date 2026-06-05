package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.providerconnection.ProviderAuthMethod;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ResolvedProviderConnection;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionStatus;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayChatRequest;
import com.rft.chat_sribiddi.providers.ProviderGatewayClient.GatewayStreamEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayChatServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private MessageRepository messageRepository;
  @Mock private ChatMessageService chatMessageService;
  @Mock private UserSettingsService userSettingsService;
  @Mock private CredentialAccessService credentialAccessService;
  @Mock private ProviderConnectionService providerConnectionService;
  @Mock private ProviderGatewayClient providerGatewayClient;
  @Mock private StreamConcurrencyGate streamConcurrencyGate;

  private GatewayChatService service;

  @BeforeEach
  void setUp() {
    service =
        new GatewayChatService(
            chatRepository,
            messageRepository,
            chatMessageService,
            new ChatProperties(),
            userSettingsService,
            credentialAccessService,
            providerConnectionService,
            new ProviderCatalogService(),
            providerGatewayClient,
            streamConcurrencyGate);
  }

  @Test
  void sendMessage_usesUserOwnedGatewayConnectionAndPersistsAssistantMessage() {
    UUID chatId = UUID.randomUUID();
    String userId = "user-123";
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setUserId(userId);
    MessageEntity userMessage = new MessageEntity();
    userMessage.setId(10L);
    userMessage.setChatId(chatId);
    userMessage.setMessage("hello");
    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setId(11L);
    when(chatRepository.findByChatIdAndUserId(chatId, userId)).thenReturn(Optional.of(chat));
    when(providerConnectionService.resolveReadyConnection(userId, "mock", 44L))
        .thenReturn(Optional.of(gatewayConnection()));
    when(chatMessageService.persistUserMessage(chatId, "hello", null)).thenReturn(userMessage);
    when(chatMessageService.autoTitleChatIfNeeded(chat, "hello")).thenReturn("hello");
    when(userSettingsService.getCustomInstructions(userId)).thenReturn(Optional.empty());
    when(credentialAccessService.authenticatedLimits())
        .thenReturn(new AccessLimits(0, 0, 0, 20, Set.of(), 1));
    when(messageRepository.findContextMessagesBefore(any(), any(), any())).thenReturn(List.of());
    when(chatMessageService.saveMessageOrThrowIfChatMissing(any())).thenReturn(assistantMessage);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Consumer<GatewayStreamEvent> consumer = invocation.getArgument(1);
              consumer.accept(
                  new GatewayStreamEvent("message", "text", "Hi", null, null, null, new ObjectMapper().createObjectNode()));
              consumer.accept(
                  new GatewayStreamEvent("done", "done", null, 2, 1, 3, new ObjectMapper().createObjectNode()));
              return null;
            })
        .when(providerGatewayClient)
        .streamChat(any(), any());

    var response =
        service.sendMessage(
            chatId,
            userId,
            new RequestDTO(
                "hello", null, "mock", "mock-model", 44L, null, null, false, null, null));

    assertThat(response.message()).isEqualTo("Hi");
    assertThat(response.promptTokens()).isEqualTo(2L);
    assertThat(response.generationTokens()).isEqualTo(1L);
    assertThat(response.totalTokens()).isEqualTo(3L);
    assertThat(response.chatTitle()).isEqualTo("hello");
    ArgumentCaptor<GatewayChatRequest> requestCaptor =
        ArgumentCaptor.forClass(GatewayChatRequest.class);
    verify(providerGatewayClient).streamChat(requestCaptor.capture(), any());
    assertThat(requestCaptor.getValue().providerId()).isEqualTo("mock");
    assertThat(requestCaptor.getValue().baseUrl()).isEqualTo("https://provider.example/v1");
  }

  private ResolvedProviderConnection gatewayConnection() {
    return new ResolvedProviderConnection(
        44L,
        "mock",
        ProviderAuthMethod.OPENAI_COMPATIBLE,
        ProviderConnectionStatus.READY,
        "sk-test",
        "https://provider.example/v1",
        "mock-model",
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        null,
        null,
        Instant.now().plusSeconds(3600),
        List.of(),
        "Mock");
  }
}
