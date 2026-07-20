import type { ModelMessage, ToolSet, UserContent } from "ai";
import type { Context } from "hono";
import { streamSSE } from "hono/streaming";
import { env } from "../config/env.js";
import type { MessageRow, NewMessage, Provider, Role } from "../db/types.js";
import { badRequest, HttpError, notFound } from "../errors/http-error.js";
import {
  type AssistantToolLogInput,
  type ChatRepository,
  chatDto,
  type MessageRepository,
} from "../repositories/chat-repository.js";
import type { ToolCallLogRepository } from "../repositories/tool-repository.js";
import { hasText, providerIdToLegacy } from "../utils/strings.js";
import { toIso } from "../utils/time.js";
import type { AiChatService, RuntimeOptions } from "./ai-chat-service.js";
import type { ApiKeyService } from "./api-key-service.js";
import type { CredentialAccessService, ResolvedCredential } from "./credential-access-service.js";
import type { DocumentService } from "./document-service.js";
import type { GuestQuotaService } from "./guest-quota-service.js";
import type { ProviderConnectionService } from "./provider-connection-service.js";
import type { StreamConcurrencyGate } from "./stream-concurrency-gate.js";
import type {
  ExecutedToolRecord,
  ToolEventSink,
  ToolExecutionEvent,
  ToolService,
} from "./tool-service.js";
import type { TokenUsage, UsageService } from "./usage-service.js";
import type { WebSearchResult, WebSearchService } from "./web-search-service.js";

export type ChatRequest = {
  content: string;
  provider?: Provider | null | undefined;
  providerId?: string | null | undefined;
  modelId: string;
  connectionId?: number | null | undefined;
  media?: Array<{ mimeType: string; data: string }> | null | undefined;
  webSearchEnabled?: boolean | null | undefined;
  options?: RuntimeOptions | null | undefined;
  toolConsents?:
    | Array<{ toolName: string; riskLevel: string; args?: Record<string, unknown> | undefined }>
    | null
    | undefined;
};

type ImportedChat = {
  title?: string | null | undefined;
  createdAt?: string | null | undefined;
  messages?:
    | Array<{ role: string; message: string; createdAt?: string | null | undefined }>
    | null
    | undefined;
};

export class ChatService {
  private readonly streamingRecovery: Promise<void>;

  constructor(
    private readonly chats: ChatRepository,
    private readonly messages: MessageRepository,
    private readonly ai: AiChatService,
    private readonly credentialAccess: CredentialAccessService,
    private readonly providerConnections: ProviderConnectionService,
    private readonly apiKeys: ApiKeyService,
    private readonly documents: DocumentService,
    _usage: UsageService,
    private readonly toolLogs: ToolCallLogRepository,
    private readonly tools: ToolService,
    private readonly webSearch: WebSearchService,
    private readonly guestQuota: GuestQuotaService,
    private readonly concurrency: StreamConcurrencyGate,
  ) {
    // Start recovery immediately. The guarded promise prevents a temporary
    // startup DB outage from becoming an unhandled rejection; normal DB health
    // handling still rejects the request that follows.
    this.streamingRecovery = this.messages
      .recoverOrphanedStreaming()
      .then(() => undefined)
      .catch(() => undefined);
  }

  async createChat(userId: string, title?: string | null) {
    return chatDto(await this.chats.create(userId, title));
  }

  async listChats(userId: string, limit?: number | null, offset?: number | null) {
    const pageSize = normalizedLimit(limit, env.CHAT_PAGE_SIZE);
    return (await this.chats.listByUser(userId, pageSize, normalizedOffset(offset))).map(chatDto);
  }

  async importChats(userId: string, chats: ImportedChat[] | null | undefined) {
    if (!chats?.length) return [];
    const imported = [];
    for (const item of chats.filter(Boolean).slice(0, 30)) {
      const messages = (item.messages ?? []).slice(0, 100);
      const newMessages: Array<Omit<NewMessage, "chat_id">> = messages.map((message) => ({
        message: message.message,
        context: null,
        role: importRole(message.role),
        created_at: message.createdAt ?? new Date(),
        prompt_tokens: null,
        generation_tokens: null,
        total_tokens: null,
        status: "COMPLETE",
        error_code: null,
      }));
      const chat = await this.chats.createWithMessages(
        userId,
        item.title,
        item.createdAt ?? undefined,
        newMessages,
      );
      imported.push(chatDto(chat));
    }
    return imported;
  }

  async listMessages(
    chatId: string,
    userId: string,
    limit?: number | null,
    offset?: number | null,
  ) {
    await this.streamingRecovery;
    await this.requireChat(chatId, userId);
    const rows = await this.messages.listResponsesByChat(
      chatId,
      normalizedLimit(limit, env.CHAT_MESSAGE_PAGE_SIZE),
      normalizedOffset(offset),
    );
    return rows.map(messageDto);
  }

  async updateTitle(chatId: string, userId: string, title: string) {
    if (!title.trim()) throw badRequest("title must not be blank");
    const row = await this.chats.updateTitle(chatId, userId, title);
    if (!row) throw notFound(`Chat ${chatId} not found`);
    return chatDto(row);
  }

  async deleteChat(chatId: string, userId: string) {
    const row = await this.chats.delete(chatId, userId);
    if (Number(row.numDeletedRows ?? 0) === 0) throw notFound(`Chat ${chatId} not found`);
  }

  async listToolCalls(messageId: number, userId: string) {
    const message = await this.messageWithOwnedChat(messageId, userId);
    if (!message) throw notFound(`Message ${messageId} not found`);
    return (await this.toolLogs.listByMessage(messageId)).map((row) => ({
      id: row.id,
      messageId: row.message_id,
      clientId: row.client_id,
      toolName: row.tool_name,
      inputPayload: row.input_payload,
      outputPayload: row.output_payload,
      startedAt: toIso(row.started_at),
      durationMs: row.duration_ms,
      contextExcerpt: row.context_excerpt,
      status: row.status,
      errorMessage: row.error_message,
      displayType: row.display_type,
    }));
  }

  async sendMessage(chatId: string, userId: string, request: ChatRequest) {
    await this.streamingRecovery;
    const resolved = await this.resolveCredential(userId, request);
    this.concurrency.acquire(userId, resolved.credential.limits.parallelStreams);
    let placeholder: MessageRow | undefined;
    try {
      const prepared = await this.prepareAuthenticatedCall(
        chatId,
        userId,
        request,
        undefined,
        resolved,
      );
      placeholder = await this.createAssistantPlaceholder(chatId, prepared);
      const result = await this.ai.generate({
        provider: prepared.executionProvider,
        userId,
        modelId: request.modelId,
        credential: prepared.credential,
        messages: prepared.promptMessages,
        options: request.options ?? null,
        tools: prepared.aiTools,
      });
      const text = result.text ?? "";
      const usage = tokenUsage(result.usage);
      const finalized = await this.finalizeAssistant(
        placeholder.id,
        chatId,
        userId,
        request.modelId,
        text,
        prepared,
        usage,
      );
      return {
        message: text,
        promptTokens: usage.promptTokens,
        generationTokens: usage.generationTokens,
        totalTokens: usage.totalTokens,
        chatTitle: prepared.updatedTitle,
        messageId: finalized.assistant.id,
        messageStatus: "COMPLETE" as const,
        hasToolCalls: finalized.hasToolCalls,
      };
    } catch (error) {
      if (placeholder) {
        await this.messages
          .markAssistantFailed(placeholder.id, "", streamErrorType(error))
          .catch(() => undefined);
      }
      throw error;
    } finally {
      this.concurrency.release(userId);
    }
  }

  async streamMessage(c: Context, chatId: string, userId: string, request: ChatRequest) {
    await this.streamingRecovery;
    const resolved = await this.resolveCredential(userId, request);
    this.concurrency.acquire(userId, resolved.credential.limits.parallelStreams);
    const sink = createToolEventSinkRef();
    try {
      const prepared = await this.prepareAuthenticatedCall(
        chatId,
        userId,
        request,
        sink.dispatcher,
        resolved,
      );
      return streamSSE(c, async (stream) => {
        const writer = createSerializedStreamWriter(stream);
        const stopHeartbeat = startHeartbeat(writer);
        sink.attach((event) =>
          writeStreamEvent(writer, toolStreamEventName(event), toolStreamPayload(event)),
        );
        try {
          await this.runAuthenticatedStream({
            stream: writer,
            chatId,
            userId,
            request,
            prepared,
            includeChatTitle: true,
            abortSignal: c.req.raw.signal,
          });
        } finally {
          stopHeartbeat();
          sink.detach();
          this.concurrency.release(userId);
          await writer.flush();
        }
      });
    } catch (error) {
      this.concurrency.release(userId);
      throw error;
    }
  }

  async editAndStreamMessage(
    c: Context,
    chatId: string,
    userId: string,
    messageId: number,
    request: ChatRequest,
  ) {
    await this.streamingRecovery;
    const chat = await this.requireChat(chatId, userId);
    const message = await this.messages.findByIdAndChat(messageId, chatId);
    if (!message) throw notFound(`Message ${messageId} not found`);
    if (message.role !== "USER") throw badRequest("Only user messages can be edited.");
    const resolved = await this.resolveCredential(userId, request);
    const mediaContext = this.mediaContext(request.media, resolved.credential.limits);
    this.concurrency.acquire(userId, resolved.credential.limits.parallelStreams);
    const sink = createToolEventSinkRef();
    try {
      const updated = await this.messages.editAndDeleteAfter(
        messageId,
        chatId,
        request.content,
        mediaContext,
      );
      if (!updated) throw notFound(`Message ${messageId} not found`);
      const prepared = await this.preparePrompt(
        chat,
        userId,
        request,
        resolved,
        null,
        messageId,
        sink.dispatcher,
      );
      return streamSSE(c, async (stream) => {
        const writer = createSerializedStreamWriter(stream);
        const stopHeartbeat = startHeartbeat(writer);
        sink.attach((event) =>
          writeStreamEvent(writer, toolStreamEventName(event), toolStreamPayload(event)),
        );
        try {
          await this.runAuthenticatedStream({
            stream: writer,
            chatId,
            userId,
            request,
            prepared,
            includeChatTitle: false,
            abortSignal: c.req.raw.signal,
          });
        } finally {
          stopHeartbeat();
          sink.detach();
          this.concurrency.release(userId);
          await writer.flush();
        }
      });
    } catch (error) {
      this.concurrency.release(userId);
      throw error;
    }
  }

  private async runAuthenticatedStream(args: {
    stream: { writeSSE: (message: { event?: string; data: string }) => Promise<void> };
    chatId: string;
    userId: string;
    request: ChatRequest;
    prepared: Awaited<ReturnType<ChatService["preparePrompt"]>> & {
      provider: Provider;
      executionProvider: Provider | string;
      credential: ResolvedCredential;
      promptMessages: ModelMessage[];
      updatedTitle: string | null;
      webResults: WebSearchResult[];
      aiTools: ToolSet | undefined;
      toolRecords: ExecutedToolRecord[];
    };
    includeChatTitle: boolean;
    abortSignal: AbortSignal;
  }) {
    const { stream, chatId, userId, request, prepared, includeChatTitle, abortSignal } = args;
    let assistant: MessageRow | undefined;
    let content = "";
    try {
      assistant = await this.createAssistantPlaceholder(chatId, prepared);
      const result = this.ai.stream({
        provider: prepared.executionProvider,
        userId,
        modelId: request.modelId,
        credential: prepared.credential,
        messages: prepared.promptMessages,
        options: request.options ?? null,
        tools: prepared.aiTools,
        abortSignal,
      });
      let lastCheckpointAt = Date.now();
      for await (const chunk of result.textStream) {
        content += chunk;
        await writeStreamEvent(stream, "message", { type: "text", content: chunk });
        if (Date.now() - lastCheckpointAt >= 2_000) {
          await this.messages.checkpointAssistant(assistant.id, content);
          lastCheckpointAt = Date.now();
        }
      }
      const usage = tokenUsage(await result.usage);
      const finalized = await this.finalizeAssistant(
        assistant.id,
        chatId,
        userId,
        request.modelId,
        content,
        prepared,
        usage,
      );
      await writeStreamEvent(stream, "done", {
        type: "done",
        promptTokens: usage.promptTokens,
        generationTokens: usage.generationTokens,
        totalTokens: usage.totalTokens,
        ...(includeChatTitle && prepared.updatedTitle ? { chatTitle: prepared.updatedTitle } : {}),
        messageId: finalized.assistant.id,
        messageStatus: "COMPLETE",
        hasToolCalls: finalized.hasToolCalls,
      });
    } catch (error) {
      const errorCode = streamErrorType(error);
      const messageStatus = content.trim() ? "INCOMPLETE" : "FAILED";
      if (assistant) {
        await this.messages
          .markAssistantFailed(assistant.id, content, errorCode)
          .catch(() => undefined);
      }
      await writeStreamEvent(stream, "error", {
        type: "error",
        content: error instanceof Error ? error.message : "Streaming error",
        provider: prepared.provider,
        modelId: request.modelId,
        errorType: errorCode,
        incomplete: true,
        messageStatus,
        ...(assistant ? { messageId: assistant.id } : {}),
      });
    }
  }

  async streamAnonymousMessage(
    c: Context,
    request: ChatRequest & { history?: Array<{ role: Role; message: string }> | null | undefined },
  ) {
    const credential = this.credentialAccess.resolveGuestOpenRouterFree(request.modelId);
    this.validateMedia(request.media, credential.limits);
    const quotaReservation = await this.guestQuota.reserve(
      c.req.raw,
      c.req.header("X-Chat-User-Id") ?? null,
      c.get("clientIp"),
    );
    const promptMessages = this.anonymousMessages(request);
    return streamSSE(c, async (stream) => {
      const writer = createSerializedStreamWriter(stream);
      const stopHeartbeat = startHeartbeat(writer);
      let providerStarted = false;
      try {
        const result = this.ai.stream({
          provider: "OPENROUTER",
          userId: null,
          modelId: request.modelId,
          credential,
          messages: promptMessages,
          options: request.options ?? null,
          abortSignal: c.req.raw.signal,
        });
        // Only the explicit provider_started event consumes guest quota. HTTP,
        // authentication, contract, and readiness failures before that event
        // remain rollback-safe.
        providerStarted = await consumeGuestQuotaAtProviderStart(result.providerStarted, () =>
          quotaReservation.commit(),
        );
        for await (const chunk of result.textStream) {
          await writeStreamEvent(writer, "message", { type: "text", content: chunk });
        }
        const usage = tokenUsage(await result.usage);
        await writeStreamEvent(writer, "done", {
          type: "done",
          promptTokens: usage.promptTokens,
          generationTokens: usage.generationTokens,
          totalTokens: usage.totalTokens,
          messageStatus: "COMPLETE",
        });
      } catch (error) {
        await writeStreamEvent(writer, "error", {
          type: "error",
          content: error instanceof Error ? error.message : "Streaming error",
          provider: "OPENROUTER",
          modelId: request.modelId,
          errorType: streamErrorType(error),
          incomplete: true,
          messageStatus: "INCOMPLETE",
        });
      } finally {
        stopHeartbeat();
        if (!providerStarted) {
          await quotaReservation.rollback();
        }
        await writer.flush();
      }
    });
  }

  async exportRunbook(chatId: string, userId: string) {
    const chat = await this.requireChat(chatId, userId);
    const messages = await this.messages.listByChat(chatId, 500, 0);
    const title = chat.title || "chat-runbook";
    const markdown = [
      `# ${title}`,
      "",
      `Chat ID: ${chat.chat_id}`,
      `Created: ${toIso(chat.created_at) ?? ""}`,
      "",
      ...messages.flatMap((message) => [
        `## ${message.role ?? "MESSAGE"} ${message.id}`,
        "",
        message.message,
        "",
      ]),
    ].join("\n");
    return {
      filename: `${
        title
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, "-")
          .replace(/^-|-$/g, "") || "chat"
      }-runbook.md`,
      markdown,
    };
  }

  private async prepareAuthenticatedCall(
    chatId: string,
    userId: string,
    request: ChatRequest,
    toolEventSink?: ToolEventSink,
    preResolved?: Awaited<ReturnType<ChatService["resolveCredential"]>>,
  ) {
    const resolved = preResolved ?? (await this.resolveCredential(userId, request));
    const mediaContext = this.mediaContext(request.media, resolved.credential.limits);
    const appended = await this.chats.appendUserMessage({
      chatId,
      userId,
      message: request.content,
      context: mediaContext,
      titleCandidate: titleForContent(request.content),
    });
    if (!appended) throw notFound(`Chat ${chatId} not found`);
    return this.preparePrompt(
      appended.chat,
      userId,
      request,
      resolved,
      appended.updatedTitle,
      appended.message.id,
      toolEventSink,
    );
  }

  private async preparePrompt(
    chat: { chat_id: string; title: string | null },
    userId: string,
    request: ChatRequest,
    resolved: Awaited<ReturnType<ChatService["resolveCredential"]>>,
    updatedTitle: string | null,
    editMessageId?: number,
    toolEventSink?: ToolEventSink,
  ) {
    const history = await this.messages.latestByChat(
      chat.chat_id,
      resolved.credential.limits.contextMessageLimit,
    );
    const documentChunks = await this.documents.relevantContext(chat.chat_id, request.content, 5);
    const braveKey =
      request.webSearchEnabled === true ? await this.apiKeys.getKey(userId, "BRAVE_SEARCH") : null;
    const webResults = braveKey ? await this.webSearch.search(request.content, braveKey) : [];
    const executableTools = await this.tools.aiTools(chat.chat_id, userId, toolEventSink);
    const promptMessages = this.promptMessages(
      history,
      request,
      documentChunks,
      webResults,
      editMessageId,
    );
    return {
      ...resolved,
      promptMessages,
      updatedTitle,
      webResults,
      aiTools: executableTools.tools,
      toolRecords: executableTools.records,
    };
  }

  private async resolveCredential(userId: string, request: ChatRequest) {
    validateRequest(request);
    const provider = request.provider ?? providerIdToLegacy(request.providerId ?? "");
    if (provider) {
      const credential = await this.credentialAccess.resolveUserCredential(
        userId,
        provider,
        request.modelId,
        request.connectionId,
      );
      return { provider, executionProvider: provider, credential };
    }
    const providerId = request.providerId?.trim();
    if (!providerId) throw badRequest("provider or providerId is required");
    const connection = await this.providerConnections.resolveReadyConnection(
      userId,
      providerId,
      request.connectionId,
    );
    if (!connection) throw badRequest("Provider connection is not available for this user");
    const credential: ResolvedCredential = {
      apiKey: connection.apiKey ?? null,
      credentialSource:
        connection.authMethod === "OAUTH_AUTH_CODE" || connection.authMethod === "OAUTH_PKCE"
          ? "USER_OAUTH"
          : connection.authMethod === "DEVICE_CODE"
            ? "USER_DEVICE_OAUTH"
            : connection.authMethod === "SUBSCRIPTION_CLI"
              ? "USER_SUBSCRIPTION_CLI"
              : connection.authMethod === "LOCAL_ENDPOINT"
                ? "USER_LOCAL_ENDPOINT"
                : "USER_KEY",
      limits: this.credentialAccess.authenticatedLimits(),
      connection,
    };
    return { provider: "OPENROUTER" as Provider, executionProvider: providerId, credential };
  }

  private async requireChat(chatId: string, userId: string) {
    const chat = await this.chats.findByIdAndUser(chatId, userId);
    if (!chat) throw notFound(`Chat ${chatId} not found`);
    return chat;
  }

  private async messageWithOwnedChat(messageId: number, userId: string) {
    return this.messages.findByIdAndUser(messageId, userId);
  }

  private mediaContext(
    media: ChatRequest["media"],
    limits: { maxAttachments: number; maxAttachmentBytes: number; maxTotalAttachmentBytes: number },
  ) {
    const items = this.validateMedia(media, limits);
    if (items.length === 0) return null;
    return `attachments:${items.map((item) => item.mimeType).join(",")}`;
  }

  private validateMedia(
    media: ChatRequest["media"],
    limits: { maxAttachments: number; maxAttachmentBytes: number; maxTotalAttachmentBytes: number },
  ) {
    const items = media ?? [];
    if (items.length > limits.maxAttachments)
      throw badRequest(`media supports up to ${limits.maxAttachments} attachments`);
    let total = 0;
    for (const item of items) {
      if (!item.mimeType?.trim() || !item.data?.trim())
        throw badRequest("media items require mimeType and data");
      const bytes = Buffer.byteLength(normalizedBase64Data(item.data), "base64");
      if (bytes > limits.maxAttachmentBytes) throw badRequest("media attachment is too large");
      total += bytes;
    }
    if (total > limits.maxTotalAttachmentBytes) throw badRequest("media attachments are too large");
    return items;
  }

  private promptMessages(
    history: MessageRow[],
    request: ChatRequest,
    documentChunks: Array<{ filename: string; text: string }>,
    webResults: WebSearchResult[],
    editMessageId?: number,
  ): ModelMessage[] {
    const systemParts = [
      "You are Chat Sribiddi, a helpful AI assistant.",
      documentChunks.length
        ? `Relevant uploaded document excerpts:\n${documentChunks
            .map((chunk, index) => `[${index + 1}] ${chunk.filename}\n${chunk.text}`)
            .join("\n\n")}`
        : "",
      this.webSearch.format(webResults),
    ].filter(Boolean);
    const messages: ModelMessage[] = [{ role: "system", content: systemParts.join("\n\n") }];
    for (const row of history) {
      if (editMessageId && row.id >= editMessageId) continue;
      if (!row.role || !row.message.trim()) continue;
      messages.push({ role: roleToModel(row.role), content: row.message });
    }
    messages.push({ role: "user", content: userContent(request) });
    return messages;
  }

  private anonymousMessages(
    request: ChatRequest & { history?: Array<{ role: Role; message: string }> | null | undefined },
  ) {
    const messages: ModelMessage[] = [
      { role: "system", content: "You are Chat Sribiddi, a helpful AI assistant." },
    ];
    const historyLimit = Math.max(0, env.ACCESS_GUEST_CONTEXT_MESSAGE_LIMIT - 1);
    const priorHistory = historyLimit > 0 ? (request.history ?? []).slice(-historyLimit) : [];
    for (const item of priorHistory) {
      messages.push({ role: roleToModel(item.role), content: item.message });
    }
    messages.push({ role: "user", content: userContent(request) });
    return messages;
  }

  private createAssistantPlaceholder(
    chatId: string,
    prepared: { provider: Provider; credential: ResolvedCredential },
  ) {
    return this.messages.createAssistantPlaceholder({
      chatId,
      context: `provider:${prepared.provider};source:${prepared.credential.credentialSource}`,
    });
  }

  private async finalizeAssistant(
    messageId: number,
    chatId: string,
    userId: string,
    modelId: string,
    content: string,
    prepared: {
      provider: Provider;
      credential: ResolvedCredential;
      toolRecords: ExecutedToolRecord[];
      webResults: WebSearchResult[];
    },
    usage: TokenUsage,
  ) {
    const toolLogs = this.assistantToolLogs(prepared.toolRecords, prepared.webResults);
    const assistant = await this.messages.finalizeAssistant({
      messageId,
      chatId,
      content,
      promptTokens: usage.promptTokens,
      generationTokens: usage.generationTokens,
      totalTokens: usage.totalTokens,
      userId,
      provider: prepared.provider,
      modelId,
      credentialSource: prepared.credential.credentialSource,
      toolLogs,
    });
    return { assistant, hasToolCalls: toolLogs.length > 0 };
  }

  private assistantToolLogs(
    records: ExecutedToolRecord[],
    webResults: WebSearchResult[],
  ): AssistantToolLogInput[] {
    const toolInserts: AssistantToolLogInput[] = records.map((record) => ({
      clientId: record.clientId,
      toolName: record.toolName,
      inputPayload: record.inputPayload,
      outputPayload: record.outputPayload,
      startedAt: record.startedAt,
      durationMs: record.durationMs,
      contextExcerpt: record.contextExcerpt,
      status: record.status,
      errorMessage: record.errorMessage,
      displayType: record.displayType,
    }));
    const webInsert: AssistantToolLogInput[] =
      webResults.length > 0
        ? [
            {
              toolName: "brave-search",
              inputPayload: JSON.stringify({}),
              outputPayload: JSON.stringify(webResults),
              durationMs: 0,
              contextExcerpt: webResults
                .map((item) => item.title)
                .join("\n")
                .slice(0, 1000),
              status: "SUCCESS",
              displayType: "search-results",
            },
          ]
        : [];
    return [...toolInserts, ...webInsert];
  }
}

function titleForContent(content: string) {
  return content.length > 72 ? `${content.slice(0, 72).trim()}...` : content.trim();
}

function validateRequest(request: ChatRequest) {
  if (!request.content?.trim()) throw badRequest("content must not be blank");
  if (!request.modelId?.trim()) throw badRequest("modelId must not be blank");
  if (!request.provider && !hasText(request.providerId))
    throw badRequest("provider or providerId is required");
}

function normalizedLimit(value: number | null | undefined, max: number) {
  return value && value > 0 ? Math.min(value, max) : max;
}

function normalizedOffset(value: number | null | undefined) {
  return value && value > 0 ? value : 0;
}

function importRole(value: string): Role {
  const normalized = value?.trim().toUpperCase();
  if (normalized === "USER" || normalized === "ASSISTANT" || normalized === "SYSTEM")
    return normalized;
  throw badRequest(`Unsupported import role: ${value}`);
}

function roleToModel(role: Role): "system" | "user" | "assistant" {
  return role === "ASSISTANT" ? "assistant" : role === "SYSTEM" ? "system" : "user";
}

export function userContent(request: Pick<ChatRequest, "content" | "media">): UserContent {
  const media = request.media ?? [];
  if (media.length === 0) return request.content;
  const parts: UserContent = [{ type: "text", text: request.content }];
  for (const item of media) {
    const data = normalizedBase64Data(item.data);
    const mediaType = item.mimeType.trim().toLowerCase();
    if (mediaType.startsWith("image/")) {
      parts.push({ type: "image", image: data, mediaType });
    } else {
      parts.push({ type: "file", data, mediaType });
    }
  }
  return parts;
}

export function normalizedBase64Data(data: string) {
  const trimmed = data.trim();
  const dataUrlMatch = /^data:([^;,]+)?;base64,(.*)$/is.exec(trimmed);
  const base64 = dataUrlMatch ? (dataUrlMatch[2] ?? "") : trimmed;
  if (
    !base64 ||
    Buffer.from(base64, "base64").toString("base64").replace(/=+$/, "") !==
      base64.replace(/=+$/, "")
  ) {
    throw badRequest("media data must be valid base64");
  }
  return base64;
}

function messageDto(row: MessageRow & { hasToolCalls?: boolean }) {
  return {
    id: row.id,
    chatId: row.chat_id,
    message: row.message,
    context: row.context,
    role: row.role,
    createdAt: toIso(row.created_at),
    promptTokens: row.prompt_tokens,
    generationTokens: row.generation_tokens,
    totalTokens: row.total_tokens,
    status: row.status,
    ...(row.error_code ? { errorCode: row.error_code } : {}),
    hasToolCalls: row.hasToolCalls === true,
  };
}

function tokenUsage(value: unknown): TokenUsage {
  const usage = (value ?? {}) as {
    inputTokens?: number;
    outputTokens?: number;
    totalTokens?: number;
    promptTokens?: number;
    completionTokens?: number;
  };
  const prompt = usage.inputTokens ?? usage.promptTokens ?? null;
  const generation = usage.outputTokens ?? usage.completionTokens ?? null;
  return {
    promptTokens: prompt,
    generationTokens: generation,
    totalTokens:
      usage.totalTokens ??
      (prompt != null || generation != null ? (prompt ?? 0) + (generation ?? 0) : null),
  };
}

async function writeStreamEvent(
  stream: { writeSSE: (message: { event?: string; data: string }) => Promise<void> },
  event: string,
  payload: unknown,
) {
  await stream.writeSSE({ event, data: JSON.stringify(payload) });
}

function createSerializedStreamWriter(stream: {
  writeSSE: (message: { event?: string; data: string }) => Promise<void>;
}) {
  let queue = Promise.resolve();
  return {
    writeSSE(message: { event?: string; data: string }) {
      const pending = queue.catch(() => undefined).then(() => stream.writeSSE(message));
      queue = pending;
      return pending;
    },
    flush() {
      return queue.catch(() => undefined);
    },
  };
}

export async function consumeGuestQuotaAtProviderStart(
  providerStarted: Promise<void>,
  commit: () => void,
) {
  await providerStarted;
  commit();
  return true;
}

function startHeartbeat(stream: {
  writeSSE: (message: { event?: string; data: string }) => Promise<void>;
}) {
  const timer = setInterval(() => {
    void writeStreamEvent(stream, "heartbeat", {
      type: "heartbeat",
      timestamp: new Date().toISOString(),
    }).catch(() => undefined);
  }, env.CHAT_STREAM_HEARTBEAT_MS);
  timer.unref?.();
  return () => clearInterval(timer);
}

function toolStreamEventName(event: ToolExecutionEvent) {
  if (event.status === "RUNNING") return "tool_call_started";
  if (event.status === "SUCCESS") return "tool_call_completed";
  return "tool_call_failed";
}

function toolStreamPayload(event: ToolExecutionEvent) {
  return {
    type: toolStreamEventName(event),
    clientId: event.clientId,
    toolName: event.toolName,
    inputPayload: event.inputPayload,
    outputPayload: event.outputPayload,
    startedAt: toIso(event.startedAt),
    durationMs: event.durationMs,
    contextExcerpt: event.contextExcerpt,
    status: event.status,
    errorMessage: event.errorMessage,
    displayType: event.displayType,
  };
}

function streamErrorType(error: unknown) {
  return error instanceof HttpError ? error.code : "UPSTREAM_ERROR";
}

/**
 * A two-stage event sink: `dispatcher` is given to the service layer at
 * prepare time (before the SSE stream exists), and `attach` swaps in the
 * real writer once streaming starts. `detach` clears the writer so any
 * lingering async tool events after stream completion are dropped.
 */
function createToolEventSinkRef() {
  let target: ToolEventSink | undefined;
  return {
    dispatcher: (event: ToolExecutionEvent) => {
      target?.(event);
    },
    attach(sink: ToolEventSink) {
      target = sink;
    },
    detach() {
      target = undefined;
    },
  };
}
