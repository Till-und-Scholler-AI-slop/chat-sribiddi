package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.ManagedQuotaReservation;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;

@ExtendWith(MockitoExtension.class)
class ChatRequestResolverTest {

  @Mock private BillingModeResolver billingModeResolver;
  @Mock private EntitlementService entitlementService;
  @Mock private ProviderClientFactory providerClientFactory;
  @Mock private DocumentRetrievalService documentRetrievalService;
  @Mock private DocumentContextFormatter documentContextFormatter;
  @Mock private PromptToolBinder promptToolBinder;
  @Mock private PromptMessageBuilder promptMessageBuilder;
  @Mock private MediaValidationService mediaValidationService;
  @Mock private UserSettingsService userSettingsService;
  @Mock private ChatMessageService chatMessageService;

  private ChatRequestResolver chatRequestResolver;

  @BeforeEach
  void setUp() {
    chatRequestResolver =
        new ChatRequestResolver(
            billingModeResolver,
            entitlementService,
            providerClientFactory,
            documentRetrievalService,
            documentContextFormatter,
            promptToolBinder,
            promptMessageBuilder,
            mediaValidationService,
            userSettingsService,
            chatMessageService);
  }

  // ---------------------------------------------------------------------------
  // resolveCall
  // ---------------------------------------------------------------------------

  @Test
  void resolveCall_byokPath_returnsUserKeyAndNoopReservation() {
    String userId = "user-1";
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, false);
    Entitlements entitlements = freeEntitlements();

    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.AUTO);
    when(billingModeResolver.resolve(userId, RequestDTO.Provider.OPENAI, "gpt-5.2", BillingMode.AUTO))
        .thenReturn(new BillingModeResolver.ResolvedKey("user-api-key", BillingMode.BYOK));
    when(entitlementService.get(userId)).thenReturn(entitlements);

    ResolvedCall result = chatRequestResolver.resolveCall(userId, requestDTO);

    assertThat(result.apiKey()).isEqualTo("user-api-key");
    assertThat(result.billingMode()).isEqualTo(BillingMode.BYOK);
    assertThat(result.entitlements()).isEqualTo(entitlements);
    assertThat(result.quotaReservation()).isNotNull();
    verify(entitlementService, never()).reserveManagedCall(userId);
  }

  @Test
  void resolveCall_managedPath_returnsServerKeyAndReservedQuota() {
    String userId = "user-1";
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENROUTER, "openrouter/free", null, null, false);
    Entitlements entitlements = freeEntitlements();
    ManagedQuotaReservation reservation = ManagedQuotaReservation.noop();

    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.AUTO);
    when(
            billingModeResolver.resolve(
                userId, RequestDTO.Provider.OPENROUTER, "openrouter/free", BillingMode.AUTO))
        .thenReturn(new BillingModeResolver.ResolvedKey("server-api-key", BillingMode.MANAGED));
    when(entitlementService.get(userId)).thenReturn(entitlements);
    when(entitlementService.reserveManagedCall(userId)).thenReturn(reservation);

    ResolvedCall result = chatRequestResolver.resolveCall(userId, requestDTO);

    assertThat(result.apiKey()).isEqualTo("server-api-key");
    assertThat(result.billingMode()).isEqualTo(BillingMode.MANAGED);
    assertThat(result.entitlements()).isEqualTo(entitlements);
    assertThat(result.quotaReservation()).isEqualTo(reservation);
    verify(entitlementService).reserveManagedCall(userId);
  }

  @Test
  void resolveCall_propagatesPlanRestrictionException() {
    String userId = "user-1";
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, false);

    when(entitlementService.resolveBillingMode(userId)).thenReturn(BillingMode.AUTO);
    when(billingModeResolver.resolve(userId, RequestDTO.Provider.OPENAI, "gpt-5.2", BillingMode.AUTO))
        .thenThrow(
            new PlanRestrictionException(
                PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
                Plan.FREE,
                "not allowed"));

    assertThatThrownBy(() -> chatRequestResolver.resolveCall(userId, requestDTO))
        .isInstanceOf(PlanRestrictionException.class)
        .hasMessageContaining("not allowed");
  }

  // ---------------------------------------------------------------------------
  // prepareUserMessage
  // ---------------------------------------------------------------------------

  @Test
  void prepareUserMessage_withMedia_buildsBatchAndReturnsTitle() {
    UUID chatId = UUID.randomUUID();
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle(null);
    String userId = "user-1";
    RequestDTO.MediaAttachment mediaAttachment =
        new RequestDTO.MediaAttachment("image/png", "base64data");
    RequestDTO requestDTO =
        new RequestDTO("hello with image", RequestDTO.Provider.OPENAI, "gpt-5.2", null, List.of(mediaAttachment), false);
    Entitlements entitlements = freeEntitlements();

    ValidatedMediaAttachment validated =
        new ValidatedMediaAttachment("image/png", mock(Media.class), 1024);
    MessageEntity persistedMessage = new MessageEntity();
    persistedMessage.setId(42L);
    PromptMessageBatch batch =
        new PromptMessageBatch(
            List.of(), List.of(), null, null, new DocumentRetrievalContext(chatId, userId), 0);

    when(mediaValidationService.validateAndConvertAttachments(List.of(mediaAttachment), entitlements))
        .thenReturn(List.of(validated));
    when(chatMessageService.persistUserMessage(chatId, "hello with image", "attachments:image/png"))
        .thenReturn(persistedMessage);
    when(chatMessageService.autoTitleChatIfNeeded(chat, "hello with image")).thenReturn("hello with image");
    when(userSettingsService.getCustomInstructions(userId)).thenReturn(Optional.of("be concise"));
    when(mediaValidationService.mediaPayload(List.of(validated))).thenReturn(List.of());
    when(promptMessageBuilder.buildBatch(any())).thenReturn(batch);

    MessagePreparation result =
        chatRequestResolver.prepareUserMessage(chatId, chat, userId, requestDTO, entitlements);

    assertThat(result.updatedTitle()).isEqualTo("hello with image");
    assertThat(result.promptBatch()).isEqualTo(batch);
    verify(promptMessageBuilder).buildBatch(any());
  }

  @Test
  void prepareUserMessage_withoutMedia_returnsNullContextAndNoTitle() {
    UUID chatId = UUID.randomUUID();
    ChatEntity chat = new ChatEntity();
    chat.setChatId(chatId);
    chat.setTitle("Existing Title");
    String userId = "user-1";
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, false);
    Entitlements entitlements = freeEntitlements();

    MessageEntity persistedMessage = new MessageEntity();
    persistedMessage.setId(99L);
    PromptMessageBatch batch =
        new PromptMessageBatch(
            List.of(), List.of(), null, null, new DocumentRetrievalContext(chatId, userId), 0);

    when(mediaValidationService.validateAndConvertAttachments(null, entitlements))
        .thenReturn(List.of());
    when(chatMessageService.persistUserMessage(chatId, "hello", null)).thenReturn(persistedMessage);
    when(chatMessageService.autoTitleChatIfNeeded(chat, "hello")).thenReturn(null);
    when(userSettingsService.getCustomInstructions(userId)).thenReturn(Optional.empty());
    when(mediaValidationService.mediaPayload(List.of())).thenReturn(List.of());
    when(promptMessageBuilder.buildBatch(any())).thenReturn(batch);

    MessagePreparation result =
        chatRequestResolver.prepareUserMessage(chatId, chat, userId, requestDTO, entitlements);

    assertThat(result.updatedTitle()).isNull();
    assertThat(result.promptBatch()).isEqualTo(batch);
  }

  // ---------------------------------------------------------------------------
  // buildMediaContext
  // ---------------------------------------------------------------------------

  @Test
  void buildMediaContext_emptyList_returnsNull() {
    assertThat(chatRequestResolver.buildMediaContext(List.of())).isNull();
  }

  @Test
  void buildMediaContext_nonEmptyList_returnsAttachmentMimeTypes() {
    List<ValidatedMediaAttachment> media =
        List.of(
            new ValidatedMediaAttachment("image/png", mock(Media.class), 1024),
            new ValidatedMediaAttachment("application/pdf", mock(Media.class), 2048));

    assertThat(chatRequestResolver.buildMediaContext(media))
        .isEqualTo("attachments:image/png,application/pdf");
  }

  // ---------------------------------------------------------------------------
  // validateMedia
  // ---------------------------------------------------------------------------

  @Test
  void validateMedia_delegatesToMediaValidationService() {
    RequestDTO.MediaAttachment attachment =
        new RequestDTO.MediaAttachment("image/png", "base64data");
    Entitlements entitlements = freeEntitlements();
    ValidatedMediaAttachment validated =
        new ValidatedMediaAttachment("image/png", mock(Media.class), 1024);

    when(mediaValidationService.validateAndConvertAttachments(List.of(attachment), entitlements))
        .thenReturn(List.of(validated));

    List<ValidatedMediaAttachment> result =
        chatRequestResolver.validateMedia(List.of(attachment), entitlements);

    assertThat(result).containsExactly(validated);
    verify(mediaValidationService).validateAndConvertAttachments(List.of(attachment), entitlements);
  }

  // ---------------------------------------------------------------------------
  // buildOptions
  // ---------------------------------------------------------------------------

  @Test
  void buildOptions_withNullOptions_delegatesToThreeArgBuildOptions() {
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, false);
    ChatOptions expectedOptions = mock(ChatOptions.class);

    when(providerClientFactory.buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", "api-key-123"))
        .thenReturn(expectedOptions);

    ChatOptions result = chatRequestResolver.buildOptions(requestDTO, "api-key-123");

    assertThat(result).isEqualTo(expectedOptions);
    verify(providerClientFactory).buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", "api-key-123");
  }

  @Test
  void buildOptions_withOptions_delegatesToFourArgBuildOptions() {
    RequestDTO.ChatRuntimeOptions runtimeOptions =
        new RequestDTO.ChatRuntimeOptions(0.7, null, null, null, null, null, null);
    RequestDTO requestDTO =
        new RequestDTO("hello", RequestDTO.Provider.OPENAI, "gpt-5.2", null, null, false, runtimeOptions, null);
    ChatOptions expectedOptions = mock(ChatOptions.class);

    when(providerClientFactory.buildOptions(
            RequestDTO.Provider.OPENAI, "gpt-5.2", "api-key-123", runtimeOptions))
        .thenReturn(expectedOptions);

    ChatOptions result = chatRequestResolver.buildOptions(requestDTO, "api-key-123");

    assertThat(result).isEqualTo(expectedOptions);
    verify(providerClientFactory)
        .buildOptions(RequestDTO.Provider.OPENAI, "gpt-5.2", "api-key-123", runtimeOptions);
  }

  // ---------------------------------------------------------------------------
  // buildAssistantContext
  // ---------------------------------------------------------------------------

  @Test
  void buildAssistantContext_delegatesToPromptMessageBuilder() {
    when(promptMessageBuilder.assistantContext(5, RequestDTO.Provider.OPENAI, "gpt-5.2"))
        .thenReturn("Contains last 5 messages including system prompt");

    String result = chatRequestResolver.buildAssistantContext(5, RequestDTO.Provider.OPENAI, "gpt-5.2");

    assertThat(result).isEqualTo("Contains last 5 messages including system prompt");
    verify(promptMessageBuilder).assistantContext(5, RequestDTO.Provider.OPENAI, "gpt-5.2");
  }

  // ---------------------------------------------------------------------------
  // buildPromptBatchForEdit
  // ---------------------------------------------------------------------------

  @Test
  void buildPromptBatchForEdit_happyPath() {
    UUID chatId = UUID.randomUUID();
    String userId = "user-1";
    RequestDTO requestDTO =
        new RequestDTO("edited content", RequestDTO.Provider.CLAUDE, "claude-3-7", null, null, false);
    Entitlements entitlements = freeEntitlements();
    Long messageId = 77L;
    ValidatedMediaAttachment validated =
        new ValidatedMediaAttachment("image/jpeg", mock(Media.class), 512);
    PromptMessageBatch expectedBatch =
        new PromptMessageBatch(
            List.of(), List.of(), null, null, new DocumentRetrievalContext(chatId, userId), 0);

    when(userSettingsService.getCustomInstructions(userId)).thenReturn(Optional.of("custom"));
    when(mediaValidationService.mediaPayload(List.of(validated))).thenReturn(List.of());
    when(promptMessageBuilder.buildBatch(any())).thenReturn(expectedBatch);

    PromptMessageBatch result =
        chatRequestResolver.buildPromptBatchForEdit(
            chatId, userId, requestDTO, entitlements, messageId, List.of(validated));

    assertThat(result).isEqualTo(expectedBatch);
    verify(promptMessageBuilder).buildBatch(any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Entitlements freeEntitlements() {
    return new Entitlements(
        Plan.FREE,
        5,
        5_242_880L,
        20_971_520L,
        20,
        Set.of("*"),
        100,
        1,
        null,
        null);
  }
}
