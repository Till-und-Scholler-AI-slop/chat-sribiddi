package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.access.AccessLimits;
import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.auth.CredentialSource;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.service.MediaValidationService.ValidatedMediaAttachment;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.DocumentRetrievalContext;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.content.Media;

@ExtendWith(MockitoExtension.class)
class ChatRequestResolverTest {

  @Mock private CredentialAccessService credentialAccessService;
  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private DocumentRetrievalService documentRetrievalService;
  @Mock private DocumentContextFormatter documentContextFormatter;
  @Mock private PromptToolBinder promptToolBinder;
  @Mock private PromptMessageBuilder promptMessageBuilder;
  @Mock private MediaValidationService mediaValidationService;
  @Mock private UserSettingsService userSettingsService;
  @Mock private ChatMessageService chatMessageService;

  private ChatRequestResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new ChatRequestResolver(
            credentialAccessService,
            providerClientFactory,
            documentRetrievalService,
            documentContextFormatter,
            promptToolBinder,
            promptMessageBuilder,
            mediaValidationService,
            userSettingsService,
            chatMessageService);
  }

  @Test
  void resolveCall_requiresStoredUserCredential() {
    AccessLimits limits = accessLimits();
    RequestDTO request =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.5", null, null, false);
    when(credentialAccessService.resolveUserCredential("user-1", RequestDTO.Provider.OPENAI, "gpt-5.5"))
        .thenReturn(
            new CredentialAccessService.ResolvedCredential(
                "sk-user", CredentialSource.USER_KEY, limits));

    ResolvedCall resolved = resolver.resolveCall("user-1", request);

    assertThat(resolved.apiKey()).isEqualTo("sk-user");
    assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.USER_KEY);
    assertThat(resolved.accessLimits()).isEqualTo(limits);
    resolved.quotaReservation().commit();
  }

  @Test
  void prepareUserMessage_passesAccessLimitsToMediaAndPromptBuilder() {
    UUID chatId = UUID.randomUUID();
    String userId = "user-1";
    AccessLimits limits = accessLimits();
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    RequestDTO.MediaAttachment mediaAttachment =
        new RequestDTO.MediaAttachment("image/png", "base64data");
    RequestDTO request =
        new RequestDTO(
            "hello with image",
            RequestDTO.Provider.OPENAI,
            "gpt-5.5",
            null,
            List.of(mediaAttachment),
            false);
    ValidatedMediaAttachment validated =
        new ValidatedMediaAttachment("image/png", mock(Media.class), 1024);
    MessageEntity persisted = new MessageEntity();
    persisted.setId(42L);
    PromptMessageBatch batch =
        new PromptMessageBatch(
            List.of(), List.of(), null, null, new DocumentRetrievalContext(chatId, userId), 0);
    when(mediaValidationService.validateAndConvertAttachments(List.of(mediaAttachment), limits))
        .thenReturn(List.of(validated));
    when(chatMessageService.persistUserMessage(chatId, "hello with image", "attachments:image/png"))
        .thenReturn(persisted);
    when(chatMessageService.autoTitleChatIfNeeded(chat, "hello with image"))
        .thenReturn("hello with image");
    when(userSettingsService.getCustomInstructions(userId)).thenReturn(Optional.of("be concise"));
    when(mediaValidationService.mediaPayload(List.of(validated))).thenReturn(List.of());
    when(promptMessageBuilder.buildBatch(any())).thenReturn(batch);

    MessagePreparation result =
        resolver.prepareUserMessage(chatId, chat, userId, request, limits);

    assertThat(result.updatedTitle()).isEqualTo("hello with image");
    assertThat(result.promptBatch()).isEqualTo(batch);
    verify(promptMessageBuilder).buildBatch(any());
  }

  @Test
  void validateMedia_delegatesWithAccessLimits() {
    AccessLimits limits = accessLimits();
    RequestDTO.MediaAttachment attachment =
        new RequestDTO.MediaAttachment("image/png", "base64data");
    ValidatedMediaAttachment validated =
        new ValidatedMediaAttachment("image/png", mock(Media.class), 1024);
    when(mediaValidationService.validateAndConvertAttachments(List.of(attachment), limits))
        .thenReturn(List.of(validated));

    List<ValidatedMediaAttachment> result = resolver.validateMedia(List.of(attachment), limits);

    assertThat(result).containsExactly(validated);
    verify(mediaValidationService).validateAndConvertAttachments(List.of(attachment), limits);
  }

  private static AccessLimits accessLimits() {
    return new AccessLimits(5, 5_242_880, 20_971_520, 20, Set.of("*"), 1);
  }
}
