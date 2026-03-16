package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.MediaAttachment;
import com.rft.chat_sribiddi.chat.dto.SendMessageResponseDTO;
import com.rft.chat_sribiddi.chat.dto.StreamEventDTO;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.model.ChatEntity;
import com.rft.chat_sribiddi.chat.model.MessageEntity;
import com.rft.chat_sribiddi.chat.model.Role;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.MessageRepository;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class ChatService {

  private static final String SYSTEM_PROMPT =
      "You are a helpful assistant. Your answer must be structured in markdown.";

  private final ChatRepository chatRepository;
  private final MessageRepository messageRepository;
  private final ApiKeyService apiKeyService;
  private final ProviderClientFactory providerClientFactory;
  private final ChatProperties chatProperties;

  public ChatService(
      ChatRepository chatRepository,
      MessageRepository messageRepository,
      ApiKeyService apiKeyService,
      ProviderClientFactory providerClientFactory,
      ChatProperties chatProperties) {
    this.chatRepository = chatRepository;
    this.messageRepository = messageRepository;
    this.apiKeyService = apiKeyService;
    this.providerClientFactory = providerClientFactory;
    this.chatProperties = chatProperties;
  }

  @Transactional
  public void deleteChat(UUID chatId, String userId) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    messageRepository.deleteByChatId(chat.getChatId());
    chatRepository.deleteById(chat.getChatId());
  }

  @Transactional
  public ChatEntity createChat(ChatEntity chatEntity, String userId) {
    chatEntity.setUserId(userId);
    return chatRepository.save(chatEntity);
  }

  public List<ChatEntity> listChats(String userId) {
    return chatRepository.findByUserIdOrderByCreatedAtDesc(userId);
  }

  public List<MessageEntity> listMessages(UUID chatId, String userId) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));
    return messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);
  }

  @Transactional
  public ChatEntity updateChatTitle(UUID chatId, String userId, String title) {
    ChatEntity chat =
        chatRepository
            .findByChatIdAndUserId(chatId, userId)
            .orElseThrow(() -> new ChatNotFoundException(chatId));

    chat.setTitle(title.trim());
    return chatRepository.save(chat);
  }

  public SendMessageResponseDTO sendMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));

    List<MediaAttachment> validatedMedia = validateMediaAttachments(requestDTO.media());

    String userContext =
        validatedMedia.isEmpty()
            ? null
            : "attachments:"
                + validatedMedia.stream()
                    .map(RequestDTO.MediaAttachment::mimeType)
                    .collect(Collectors.joining(","));

    MessageEntity persistedUserMessage =
        persistMessage(chatId, requestDTO.content(), Role.USER, userContext);

    List<Message> promptMessages =
        buildPromptMessages(chatId, validatedMedia, persistedUserMessage.getId());
    if (log.isTraceEnabled()) {
      log.trace("Prompt messages: {}", promptMessages);
    }

    String resolvedKey =
        Optional.ofNullable(requestDTO.apiKey())
            .filter(key -> !key.isBlank())
            .or(() -> apiKeyService.getKey(userId, requestDTO.provider()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No API key supplied and no stored key found for provider %s"
                            .formatted(requestDTO.provider())));

    ChatResponse chatResponse;
    try (ClientHolder clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedKey)) {
      var options =
          providerClientFactory.buildOptions(
              requestDTO.provider(), requestDTO.modelId(), resolvedKey);
      var client = clientHolder.client();
      var responseSpec = client.prompt().options(options).messages(promptMessages).call();
      chatResponse = responseSpec.chatResponse();
    }

    if (log.isTraceEnabled()) {
      log.trace("Chat response: {}", chatResponse);
    }

    String assistantResponse =
        Optional.ofNullable(chatResponse)
            .map(ChatResponse::getResult)
            .map(Generation::getOutput)
            .map(AbstractMessage::getText)
            .orElse("");

    Long promptTokens = null;
    Long generationTokens = null;
    Long totalTokens = null;

    if (chatResponse != null && chatResponse.getMetadata() != null) {
      Usage usage = chatResponse.getMetadata().getUsage();
      if (usage != null) {
        promptTokens = safeLong(usage.getPromptTokens());
        generationTokens = safeLong(usage.getCompletionTokens());
        totalTokens = safeLong(usage.getTotalTokens());
      }
    }

    MessageEntity assistantMessage = new MessageEntity();
    assistantMessage.setChatId(chatId);
    assistantMessage.setMessage(assistantResponse);
    assistantMessage.setRole(Role.ASSISTANT);
    assistantMessage.setContext(
        "Contains last %d messages including system prompt".formatted(promptMessages.size()));
    assistantMessage.setCreatedAt(Instant.now());
    assistantMessage.setPromptTokens(promptTokens);
    assistantMessage.setGenerationTokens(generationTokens);
    assistantMessage.setTotalTokens(totalTokens);
    messageRepository.save(assistantMessage);

    log.debug("Message persisted successfully");

    return new SendMessageResponseDTO(assistantResponse, promptTokens, generationTokens, totalTokens);
  }

  /**
   * Streams a chat response as Server-Sent Events. The user message is persisted before the stream
   * begins; the assistant message is persisted once the stream completes.
   */
  public SseEmitter streamMessage(UUID chatId, String userId, RequestDTO requestDTO) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ChatNotFoundException(chatId));

    List<MediaAttachment> validatedMedia = validateMediaAttachments(requestDTO.media());

    String userContext =
        validatedMedia.isEmpty()
            ? null
            : "attachments:"
                + validatedMedia.stream()
                    .map(RequestDTO.MediaAttachment::mimeType)
                    .collect(Collectors.joining(","));

    MessageEntity persistedUserMessage =
        persistMessage(chatId, requestDTO.content(), Role.USER, userContext);

    List<Message> promptMessages =
        buildPromptMessages(chatId, validatedMedia, persistedUserMessage.getId());

    String resolvedKey =
        Optional.ofNullable(requestDTO.apiKey())
            .filter(key -> !key.isBlank())
            .or(() -> apiKeyService.getKey(userId, requestDTO.provider()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No API key supplied and no stored key found for provider %s"
                            .formatted(requestDTO.provider())));

    SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());

    ClientHolder clientHolder =
        providerClientFactory.getClient(requestDTO.provider(), resolvedKey);
    var options =
        providerClientFactory.buildOptions(
            requestDTO.provider(), requestDTO.modelId(), resolvedKey);
    var client = clientHolder.client();

    StringBuilder fullText = new StringBuilder();
    AtomicReference<Usage> lastUsage = new AtomicReference<>();

    client
        .prompt()
        .options(options)
        .messages(promptMessages)
        .stream()
        .chatResponse()
        .subscribe(
            chunk -> {
              try {
                String text =
                    Optional.ofNullable(chunk)
                        .map(ChatResponse::getResult)
                        .map(Generation::getOutput)
                        .map(AbstractMessage::getText)
                        .orElse(null);

                if (text != null && !text.isEmpty()) {
                  fullText.append(text);
                  emitter.send(
                      SseEmitter.event()
                          .name("text")
                          .data(
                              new StreamEventDTO("text", text, null, null, null),
                              MediaType.APPLICATION_JSON));
                }

                String reasoning = extractReasoningContent(chunk);
                if (reasoning != null && !reasoning.isEmpty()) {
                  emitter.send(
                      SseEmitter.event()
                          .name("reasoning")
                          .data(
                              new StreamEventDTO("reasoning", reasoning, null, null, null),
                              MediaType.APPLICATION_JSON));
                }

                if (chunk != null && chunk.getMetadata() != null) {
                  Usage usage = chunk.getMetadata().getUsage();
                  if (usage != null) {
                    lastUsage.set(usage);
                  }
                }
              } catch (IOException e) {
                log.warn("Failed to send SSE event", e);
              }
            },
            error -> {
              log.error("Streaming error for chat {}", chatId, error);
              try {
                emitter.send(
                    SseEmitter.event()
                        .name("error")
                        .data(
                            new StreamEventDTO(
                                "error", error.getMessage(), null, null, null),
                            MediaType.APPLICATION_JSON));
              } catch (IOException e) {
                log.warn("Failed to send SSE error event", e);
              } finally {
                emitter.completeWithError(error);
                clientHolder.close();
              }
            },
            () -> {
              try {
                String assistantResponse = fullText.toString();
                Usage usage = lastUsage.get();

                Long promptTokens = usage != null ? safeLong(usage.getPromptTokens()) : null;
                Long generationTokens =
                    usage != null ? safeLong(usage.getCompletionTokens()) : null;
                Long totalTokens = usage != null ? safeLong(usage.getTotalTokens()) : null;

                MessageEntity assistantMessage = new MessageEntity();
                assistantMessage.setChatId(chatId);
                assistantMessage.setMessage(assistantResponse);
                assistantMessage.setRole(Role.ASSISTANT);
                assistantMessage.setContext(
                    "Contains last %d messages including system prompt"
                        .formatted(promptMessages.size()));
                assistantMessage.setCreatedAt(Instant.now());
                assistantMessage.setPromptTokens(promptTokens);
                assistantMessage.setGenerationTokens(generationTokens);
                assistantMessage.setTotalTokens(totalTokens);
                messageRepository.save(assistantMessage);

                emitter.send(
                    SseEmitter.event()
                        .name("done")
                        .data(
                            new StreamEventDTO(
                                "done", null, promptTokens, generationTokens, totalTokens),
                            MediaType.APPLICATION_JSON));
                emitter.complete();
              } catch (IOException e) {
                log.warn("Failed to send SSE done event", e);
                emitter.completeWithError(e);
              } finally {
                clientHolder.close();
              }
            });

    emitter.onTimeout(clientHolder::close);
    emitter.onError(e -> clientHolder.close());

    return emitter;
  }

  private String extractReasoningContent(ChatResponse chunk) {
    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
      return null;
    }
    Map<String, Object> metadata = chunk.getResult().getOutput().getMetadata();
    if (metadata == null) {
      return null;
    }
    Object reasoning = metadata.get("reasoningContent");
    if (reasoning instanceof String s && !s.isEmpty()) {
      return s;
    }
    return null;
  }

  private List<Message> buildPromptMessages(
      UUID chatId, List<MediaAttachment> mediaAttachments, Long targetUserMessageId) {
    List<MessageEntity> latestMessages =
        new ArrayList<>(
            messageRepository.findByChatIdOrderByCreatedAtDesc(
                chatId, PageRequest.of(0, chatProperties.getContextMessageLimit())));

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(SYSTEM_PROMPT));

    List<MessageEntity> ordered = latestMessages.reversed();
    for (MessageEntity entity : ordered) {
      if (!mediaAttachments.isEmpty()
          && targetUserMessageId != null
          && entity.getRole() == Role.USER
          && targetUserMessageId.equals(entity.getId())) {
        messages.add(toUserMessage(entity, mediaAttachments));
        continue;
      }
      messages.add(toChatMessage(entity));
    }
    return messages;
  }

  private List<MediaAttachment> validateMediaAttachments(List<MediaAttachment> mediaAttachments) {
    if (mediaAttachments == null || mediaAttachments.isEmpty()) {
      return List.of();
    }

    if (mediaAttachments.size() > chatProperties.getMaxMediaAttachments()) {
      throw new IllegalArgumentException(
          "media supports up to %d attachments".formatted(chatProperties.getMaxMediaAttachments()));
    }

    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      if (attachment == null) {
        throw new IllegalArgumentException("media[%d] must not be null".formatted(i));
      }
    }

    return mediaAttachments;
  }

  private Message toChatMessage(MessageEntity entity) {
    return switch (entity.getRole()) {
      case ASSISTANT -> new AssistantMessage(entity.getMessage());
      case SYSTEM -> new SystemMessage(entity.getMessage());
      default -> new UserMessage(entity.getMessage());
    };
  }

  private UserMessage toUserMessage(MessageEntity entity, List<MediaAttachment> mediaAttachments) {
    List<Media> media = toMediaPayload(mediaAttachments);
    return UserMessage.builder().text(entity.getMessage()).media(media).build();
  }

  private List<Media> toMediaPayload(List<MediaAttachment> mediaAttachments) {
    long totalBytes = 0;
    List<Media> media = new ArrayList<>(mediaAttachments.size());

    for (int i = 0; i < mediaAttachments.size(); i++) {
      MediaAttachment attachment = mediaAttachments.get(i);
      String normalized = normalizeEncodedData(attachment.data());
      long estimatedDecodedBytes = estimateDecodedBytes(normalized);

      if (estimatedDecodedBytes > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes"
                .formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      totalBytes += estimatedDecodedBytes;
      if (totalBytes > chatProperties.getMaxMediaTotalBytes()) {
        throw new IllegalArgumentException(
            "combined media size exceeds %d bytes".formatted(chatProperties.getMaxMediaTotalBytes()));
      }

      byte[] content;
      try {
        content = Base64.getDecoder().decode(normalized);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("media[%d].data is not valid base64".formatted(i), e);
      }

      if (content.length > chatProperties.getMaxMediaAttachmentBytes()) {
        throw new IllegalArgumentException(
            "media[%d] exceeds %d bytes".formatted(i, chatProperties.getMaxMediaAttachmentBytes()));
      }

      media.add(
          new Media(
              MimeTypeUtils.parseMimeType(attachment.mimeType()), new ByteArrayResource(content)));
    }

    return media;
  }

  private String normalizeEncodedData(String encodedData) {
    int commaIndex = encodedData.indexOf(',');
    if (encodedData.startsWith("data:") && commaIndex > 0) {
      return encodedData.substring(commaIndex + 1);
    }
    return encodedData;
  }

  private long estimateDecodedBytes(String normalizedBase64) {
    int length = normalizedBase64.length();
    int padding = 0;
    if (length > 0 && normalizedBase64.charAt(length - 1) == '=') {
      padding++;
      if (length > 1 && normalizedBase64.charAt(length - 2) == '=') {
        padding++;
      }
    }
    return ((long) length * 3 / 4) - padding;
  }

  private MessageEntity persistMessage(UUID chatId, String message, Role role, String context) {
    MessageEntity entity = new MessageEntity();
    entity.setChatId(chatId);
    entity.setMessage(message);
    entity.setRole(role);
    entity.setContext(context);
    entity.setCreatedAt(Instant.now());
    return messageRepository.save(entity);
  }

  private static Long safeLong(Number value) {
    return value == null ? null : value.longValue();
  }

  public record ClientHolder(
      org.springframework.ai.chat.client.ChatClient client, AutoCloseable closeable)
      implements AutoCloseable {
    @Override
    public void close() {
      if (closeable == null) {
        return;
      }
      try {
        closeable.close();
      } catch (Exception e) {
        log.warn("Failed to close provider client", e);
      }
    }
  }
}
