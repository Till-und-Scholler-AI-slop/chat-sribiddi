package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.GuestQuotaService;
import com.rft.chat_sribiddi.billing.GuestQuotaService.GuestQuotaReservation;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.chat.dto.AnonymousChatRequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.service.ChatStreamSubscriber.StatelessStreamSubscription;
import com.rft.chat_sribiddi.chat.service.MediaValidationService.ValidatedMediaAttachment;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.StatelessChatMessage;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.StatelessPromptMessageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousChatService {

  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;
  private final MediaValidationService mediaValidationService;
  private final PromptMessageBuilder promptMessageBuilder;
  private final BillingModeResolver billingModeResolver;
  private final EntitlementService entitlementService;
  private final GuestQuotaService guestQuotaService;
  private final ChatStreamSubscriber chatStreamSubscriber;

  public SseEmitter streamMessage(
      AnonymousChatRequestDTO request, String guestId, HttpServletRequest servletRequest) {
    if (request.provider() != Provider.OPENROUTER) {
      throw new PlanRestrictionException(
          PlanRestrictionException.Reason.MODEL_NOT_ALLOWED,
          Plan.FREE,
          "Anonymous chat is only available through OpenRouter managed models");
    }

    Entitlements entitlements = entitlementService.get(null);
    BillingModeResolver.ResolvedKey resolvedKey =
        billingModeResolver.resolve(null, request.provider(), request.modelId(), BillingMode.MANAGED);
    List<ValidatedMediaAttachment> validatedMedia =
        mediaValidationService.validateAndConvertAttachments(request.media(), entitlements);
    List<StatelessChatMessage> history = toHistory(request.history());
    PromptMessageBatch promptBatch =
        promptMessageBuilder.buildStatelessBatch(
            new StatelessPromptMessageRequest(
                history,
                request.content(),
                mediaValidationService.mediaPayload(validatedMedia),
                null,
                request.provider(),
                request.modelId(),
                entitlements,
                null));

    GuestQuotaReservation quotaReservation =
        guestQuotaService.reserveDailyCall(servletRequest, guestId);
    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
    ProviderClientHandle clientHandle = null;
    try {
      clientHandle =
          providerClientFactory.getClient(
              request.provider(), resolvedKey.apiKey(), resolvedKey.effectiveMode());
      var promptSpec = preparePromptSpec(request, promptBatch, resolvedKey.apiKey(), clientHandle);
      chatStreamSubscriber.subscribeStateless(
          new StatelessStreamSubscription(
              promptSpec,
              emitter,
              clientHandle,
              request.provider(),
              request.modelId(),
              quotaReservation));
    } catch (RuntimeException | Error error) {
      quotaReservation.rollback();
      if (clientHandle != null) {
        clientHandle.close();
      }
      throw error;
    }
    return emitter;
  }

  private List<StatelessChatMessage> toHistory(List<AnonymousChatRequestDTO.HistoryMessage> history) {
    if (history == null || history.isEmpty()) {
      return List.of();
    }
    return history.stream()
        .map(item -> new StatelessChatMessage(parseRole(item.role()), item.message()))
        .toList();
  }

  private Role parseRole(String raw) {
    String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "USER" -> Role.USER;
      case "ASSISTANT" -> Role.ASSISTANT;
      default -> throw new IllegalArgumentException("Unsupported anonymous history role: " + raw);
    };
  }

  private org.springframework.ai.chat.prompt.ChatOptions buildOptions(
      AnonymousChatRequestDTO request, String apiKey) {
    if (request.options() == null) {
      return providerClientFactory.buildOptions(request.provider(), request.modelId(), apiKey);
    }
    return providerClientFactory.buildOptions(
        request.provider(), request.modelId(), apiKey, request.options());
  }

  private org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec preparePromptSpec(
      AnonymousChatRequestDTO request,
      PromptMessageBatch promptBatch,
      String apiKey,
      ProviderClientHandle clientHandle) {
    var options = buildOptions(request, apiKey);
    var promptSpec =
        clientHandle.client().prompt().options(options.mutate()).messages(promptBatch.requestMessages());
    PromptAdvisors.bind(promptSpec, promptBatch);
    return promptSpec;
  }

}
