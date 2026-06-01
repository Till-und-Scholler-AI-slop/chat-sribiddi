package com.rft.chat_sribiddi.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.auth.BillingMode;
import com.rft.chat_sribiddi.billing.BillingModeResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.Entitlements;
import com.rft.chat_sribiddi.billing.ManagedQuotaReservation;
import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.repository.ChatToolRepository;
import com.rft.chat_sribiddi.chat.service.MediaValidationService.ValidatedMediaAttachment;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.DocumentRetrievalContext;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageBatch;
import com.rft.chat_sribiddi.chat.service.PromptMessageBuilder.PromptMessageRequest;
import com.rft.chat_sribiddi.chat.toolcall.ToolExecutionCollector;
import com.rft.chat_sribiddi.auth.UserSettingsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRequestResolver {

  private static final String ATTACHMENTS_PREFIX = "attachments:";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final BillingModeResolver billingModeResolver;
  private final EntitlementService entitlementService;
  private final ProviderClientFactory providerClientFactory;
  private final DocumentRetrievalService documentRetrievalService;
  private final DocumentContextFormatter documentContextFormatter;
  private final PromptToolBinder promptToolBinder;
  private final PromptMessageBuilder promptMessageBuilder;
  private final MediaValidationService mediaValidationService;
  private final UserSettingsService userSettingsService;
  private final ChatMessageService chatMessageService;
  private final ChatToolRepository chatToolRepository;

  public ResolvedCall resolveCall(String userId, RequestDTO requestDTO) {
    BillingMode requestedMode = entitlementService.resolveBillingMode(userId);
    BillingModeResolver.ResolvedKey resolvedKey =
        billingModeResolver.resolve(
            userId, requestDTO.provider(), requestDTO.modelId(), requestedMode);
    ManagedQuotaReservation reservation = ManagedQuotaReservation.noop();
    if (resolvedKey.effectiveMode() == BillingMode.MANAGED) {
      reservation = entitlementService.reserveManagedCall(userId);
    }
    return new ResolvedCall(
        resolvedKey.apiKey(),
        resolvedKey.effectiveMode(),
        entitlementService.get(userId),
        reservation);
  }

  public MessagePreparation prepareUserMessage(
      UUID chatId,
      ChatEntity chat,
      String userId,
      RequestDTO requestDTO,
      Entitlements entitlements) {
    List<ValidatedMediaAttachment> validatedMedia =
        mediaValidationService.validateAndConvertAttachments(requestDTO.media(), entitlements);
    String userContext = buildMediaContext(validatedMedia);
    MessageEntity persistedUserMessage =
        chatMessageService.persistUserMessage(chatId, requestDTO.content(), userContext);
    String updatedTitle = chatMessageService.autoTitleChatIfNeeded(chat, requestDTO.content());
    String customInstructions = userSettingsService.getCustomInstructions(userId).orElse(null);
    PromptMessageBatch promptBatch =
        promptMessageBuilder.buildBatch(
            new PromptMessageRequest(
                chatId,
                requestDTO.content(),
                mediaValidationService.mediaPayload(validatedMedia),
                persistedUserMessage.getId(),
                customInstructions,
                requestDTO.provider(),
                requestDTO.modelId(),
                entitlements,
                new DocumentRetrievalContext(chatId, userId)));
    return new MessagePreparation(updatedTitle, promptBatch);
  }

  public PromptMessageBatch buildPromptBatchForEdit(
      UUID chatId,
      String userId,
      RequestDTO requestDTO,
      Entitlements entitlements,
      Long messageId,
      List<ValidatedMediaAttachment> validatedMedia) {
    String customInstructions = userSettingsService.getCustomInstructions(userId).orElse(null);
    return promptMessageBuilder.buildBatch(
        new PromptMessageRequest(
            chatId,
            requestDTO.content(),
            mediaValidationService.mediaPayload(validatedMedia),
            messageId,
            customInstructions,
            requestDTO.provider(),
            requestDTO.modelId(),
            entitlements,
            new DocumentRetrievalContext(chatId, userId)));
  }

  public String buildMediaContext(List<ValidatedMediaAttachment> validatedMedia) {
    return validatedMedia.isEmpty()
        ? null
        : ATTACHMENTS_PREFIX
            + validatedMedia.stream()
                .map(ValidatedMediaAttachment::mimeType)
                .collect(Collectors.joining(","));
  }

  public List<ValidatedMediaAttachment> validateMedia(
      List<RequestDTO.MediaAttachment> media, Entitlements entitlements) {
    return mediaValidationService.validateAndConvertAttachments(media, entitlements);
  }

  public org.springframework.ai.chat.prompt.ChatOptions buildOptions(
      RequestDTO requestDTO, String apiKey) {
    if (requestDTO.options() == null) {
      return providerClientFactory.buildOptions(requestDTO.provider(), requestDTO.modelId(), apiKey);
    }
    return providerClientFactory.buildOptions(
        requestDTO.provider(), requestDTO.modelId(), apiKey, requestDTO.options());
  }

  public String buildAssistantContext(
      int totalMessageCount, RequestDTO.Provider provider, String modelId) {
    return promptMessageBuilder.assistantContext(totalMessageCount, provider, modelId);
  }

  public PreparedPrompt preparePromptSpec(
      UUID chatId,
      String userId,
      RequestDTO requestDTO,
      PromptMessageBatch promptBatch,
      String apiKey,
      ProviderClientHandle clientHolder) {
    var options = buildOptions(requestDTO, apiKey);
    var promptSpec =
        clientHolder.client().prompt().options(options.mutate()).messages(promptBatch.requestMessages());
    PromptAdvisors.bind(promptSpec, promptBatch, documentRetrievalService, documentContextFormatter);
    ToolExecutionCollector toolExecutionCollector = new ToolExecutionCollector();
    recordDocumentAnalysisArtifact(chatId, userId, requestDTO.content(), toolExecutionCollector);
    promptToolBinder.bindTools(
        chatId,
        userId,
        requestDTO.provider(),
        requestDTO.modelId(),
        requestDTO.webSearchEnabled(),
        requestDTO.toolConsents(),
        toolExecutionCollector,
        promptSpec);
    return new PreparedPrompt(promptSpec, toolExecutionCollector);
  }

  private void recordDocumentAnalysisArtifact(
      UUID chatId, String userId, String query, ToolExecutionCollector collector) {
    if (!isDocumentAnalysisEnabled(chatId, userId)) {
      return;
    }
    try {
      List<org.springframework.ai.document.Document> documents =
          documentRetrievalService.retrieveDocuments(chatId, userId, query);
      if (documents.isEmpty()) {
        return;
      }
      List<Map<String, Object>> chunks =
          documents.stream()
              .limit(5)
              .map(
                  document ->
                      Map.<String, Object>of(
                          "documentId",
                          String.valueOf(document.getMetadata().get("document_id")),
                          "chunkIndex",
                          String.valueOf(document.getMetadata().get("chunk_index")),
                          "pageStart",
                          String.valueOf(document.getMetadata().getOrDefault("page_start", "")),
                          "pageEnd",
                          String.valueOf(document.getMetadata().getOrDefault("page_end", "")),
                          "excerpt",
                          excerpt(document.getText())))
              .toList();
      collector.recordSynthetic(
          "DocumentAnalysis.retrieve",
          "document",
          Map.of("query", query == null ? "" : query),
          MAPPER.writeValueAsString(
              Map.of(
                  "type",
                  "document",
                  "chunkCount",
                  documents.size(),
                  "chunks",
                  chunks)));
    } catch (RuntimeException | JsonProcessingException ex) {
      log.debug("Unable to record document analysis tool artifact", ex);
    }
  }

  private boolean isDocumentAnalysisEnabled(UUID chatId, String userId) {
    try {
      if (!entitlementService.get(userId).allowsTool("document-analysis")) {
        return false;
      }
      return chatToolRepository.findByChatId(chatId).stream()
          .filter(tool -> "document-analysis".equals(tool.getToolName()))
          .findFirst()
          .map(ChatToolEntity::isEnabled)
          .orElse(false);
    } catch (RuntimeException ex) {
      log.debug("Unable to resolve document-analysis tool gate", ex);
      return false;
    }
  }

  private String excerpt(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String normalized = text.trim().replaceAll("\\s+", " ");
    return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
  }
}
