package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.AccessRestrictionException;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.billing.GuestQuotaService;
import com.rft.chat_sribiddi.chat.dto.AnonymousChatRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

@ExtendWith(MockitoExtension.class)
class AnonymousChatServiceTest {

  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private MessageRepository messageRepository;
  @Mock private CredentialAccessService credentialAccessService;
  @Mock private GuestQuotaService guestQuotaService;
  @Mock private GuestQuotaService.GuestQuotaReservation quotaReservation;
  @Mock private HttpServletRequest servletRequest;
  @Mock private ChatClient chatClient;
  @Mock private ChatClient.ChatClientRequestSpec requestSpec;
  @Mock private ChatStreamSubscriber chatStreamSubscriber;

  private AnonymousChatService service;

  @BeforeEach
  void setUp() {
    ChatProperties chatProperties = new ChatProperties();
    MediaValidationService mediaValidationService = new MediaValidationService(chatProperties);
    PromptMessageBuilder promptMessageBuilder =
        new PromptMessageBuilder(messageRepository, chatProperties);
    service =
        new AnonymousChatService(
            providerClientFactory,
            chatProperties,
            mediaValidationService,
            promptMessageBuilder,
            new DocumentContextFormatter(new RagProperties()),
            credentialAccessService,
            guestQuotaService,
            chatStreamSubscriber);
  }

  @Test
  void streamMessage_bindsOpenRouterFreeWithGuestCredentialSource() {
    AccessLimits limits = new AccessLimits(2, 1024, 2048, 10, Set.of(), 1);
    when(credentialAccessService.resolveGuestOpenRouterFree("openrouter/free"))
        .thenReturn(
            new CredentialAccessService.ResolvedCredential(
                "server-key", CredentialSource.GUEST_OPENROUTER_FREE, limits));
    when(guestQuotaService.reserveDailyCall(servletRequest, "guest-1"))
        .thenReturn(quotaReservation);
    when(providerClientFactory.getClient(
            Provider.OPENROUTER, "server-key", CredentialSource.GUEST_OPENROUTER_FREE))
        .thenReturn(new ProviderClientHandle(chatClient, null));
    when(providerClientFactory.buildOptions(Provider.OPENROUTER, "openrouter/free", "server-key"))
        .thenReturn(ChatOptions.builder().model("openrouter/free").build());
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    AnonymousChatRequestDTO request =
        new AnonymousChatRequestDTO(
            "current request",
            Provider.OPENROUTER,
            "openrouter/free",
            List.of(new AnonymousChatRequestDTO.HistoryMessage("USER", "previous request")),
            List.of());

    service.streamMessage(request, "guest-1", servletRequest);

    ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
    verify(requestSpec).messages(messagesCaptor.capture());
    assertThat(((UserMessage) messagesCaptor.getValue().getLast()).getText())
        .isEqualTo("current request");
    verify(requestSpec).advisors(any(Consumer.class));
    verify(chatStreamSubscriber)
        .subscribeStateless(any(ChatStreamSubscriber.StatelessStreamSubscription.class));
  }

  @Test
  void streamMessage_rejectsNonOpenRouterBeforeQuotaReservation() {
    AnonymousChatRequestDTO request =
        new AnonymousChatRequestDTO(
            "hello", Provider.OPENAI, "gpt-5.5", List.of(), List.of());

    assertThatThrownBy(() -> service.streamMessage(request, "guest-1", servletRequest))
        .isInstanceOfSatisfying(
            AccessRestrictionException.class,
            ex -> assertThat(ex.getReason()).isEqualTo(AccessRestrictionException.Reason.MODEL_NOT_ALLOWED));
    verify(guestQuotaService, never()).reserveDailyCall(servletRequest, "guest-1");
  }
}
