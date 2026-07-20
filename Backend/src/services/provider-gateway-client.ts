import { randomUUID } from "node:crypto";
import {
  type GatewayJsonValue,
  type GatewayMessage,
  type GatewayProvider,
  type GatewayTool,
  PROVIDER_CONTRACT_VERSION,
  PROVIDER_STREAM_PATH,
  type ProviderStreamEvent,
  type ProviderStreamRequest,
  ProviderStreamRequestSchema,
  parseProviderStreamEvent,
} from "@chat-sribiddi/provider-contract";
import { asSchema, type ModelMessage, type ToolSet } from "ai";
import { env } from "../config/env.js";
import type { Provider } from "../db/types.js";
import {
  providerRequestsTotal,
  providerTokensTotal,
  streamDuration,
  streamTimeToFirstToken,
} from "../observability/metrics.js";
import type { ResolvedCredential } from "./credential-access-service.js";

export const PROVIDER_GATEWAY_REQUEST_LIMIT_BYTES = 32 * 1024 * 1024;
const PROVIDER_GATEWAY_EVENT_LIMIT_BYTES = 4 * 1024 * 1024;
const PROVIDER_GATEWAY_ERROR_LIMIT_BYTES = 64 * 1024;
const PROVIDER_GATEWAY_MAX_DEADLINE_MS = 30 * 60_000;
const PROVIDER_GATEWAY_MAX_MODEL_STEPS = 5;

export type ProviderGatewayRuntimeOptions = {
  temperature?: number | null | undefined;
  topP?: number | null | undefined;
  topK?: number | null | undefined;
  maxTokens?: number | null | undefined;
  frequencyPenalty?: number | null | undefined;
  presencePenalty?: number | null | undefined;
  stopSequences?: string[] | null | undefined;
};

export type ProviderGatewayExecutionRequest = {
  userId?: string | null | undefined;
  provider: Provider | string;
  modelId: string;
  credential: ResolvedCredential;
  messages: ModelMessage[];
  options?: ProviderGatewayRuntimeOptions | null | undefined;
  tools?: ToolSet | undefined;
  abortSignal?: AbortSignal | undefined;
};

export type ProviderGatewayUsage = {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  reasoningTokens?: number | null;
  cachedInputTokens?: number | null;
};

export type ProviderGatewayStreamResult = {
  textStream: AsyncIterable<string>;
  usage: Promise<ProviderGatewayUsage>;
  providerStarted: Promise<void>;
};

export type ProviderGatewayClientOptions = {
  baseUrl?: string | undefined;
  sharedSecret?: string | undefined;
  fetch?: typeof fetch | undefined;
  deadlineMs?: number | undefined;
  maxQueueChunks?: number | undefined;
  maxQueueBytes?: number | undefined;
};

type GatewayAssistantContent = Exclude<
  Extract<GatewayMessage, { role: "assistant" }>["content"],
  string
>;
type GatewayToolContent = Extract<GatewayMessage, { role: "tool" }>["content"];

export class ProviderGatewayError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryable = false,
    readonly providerStarted = false,
    options?: ErrorOptions,
  ) {
    super(safeErrorMessage(message), options);
    this.name = "ProviderGatewayError";
  }
}

export class ProviderGatewayClient {
  private readonly baseUrl: string;
  private readonly sharedSecret: string | undefined;
  private readonly fetchImpl: typeof fetch;
  private readonly deadlineMs: number;
  private readonly maxQueueChunks: number;
  private readonly maxQueueBytes: number;

  constructor(options: ProviderGatewayClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? env.PROVIDER_GATEWAY_BASE_URL).replace(/\/$/, "");
    this.sharedSecret = options.sharedSecret ?? env.PROVIDER_GATEWAY_SHARED_SECRET;
    this.fetchImpl = options.fetch ?? globalThis.fetch;
    this.deadlineMs = clampDeadline(options.deadlineMs ?? env.CHAT_STREAM_TIMEOUT_MS);
    this.maxQueueChunks = positiveInteger(options.maxQueueChunks, 64);
    this.maxQueueBytes = positiveInteger(options.maxQueueBytes, 1_048_576);
  }

  stream(input: ProviderGatewayExecutionRequest): ProviderGatewayStreamResult {
    const startedAt = performance.now();
    const providerLabel = gatewayRouteKey(input);
    const providerStarted = createDeferred<void>();
    const usage = createDeferred<ProviderGatewayUsage>();
    const queue = new BoundedAsyncQueue(this.maxQueueChunks, this.maxQueueBytes);
    const linked = linkedAbortController(input.abortSignal, this.deadlineMs);
    let producerSettled = false;
    let firstContentObserved = false;

    const observeFirstContent = () => {
      if (firstContentObserved) return;
      firstContentObserved = true;
      streamTimeToFirstToken.labels(providerLabel).observe((performance.now() - startedAt) / 1_000);
    };

    void this.runExecution(input, linked.controller.signal, {
      onProviderStarted: () => providerStarted.resolve(),
      onText: async (delta) => {
        observeFirstContent();
        await queue.push(delta, linked.controller.signal);
      },
      onProviderContent: observeFirstContent,
    })
      .then((resultUsage) => {
        producerSettled = true;
        providerStarted.reject(
          new ProviderGatewayError(
            "missing_provider_started",
            "ProviderGateway completed without provider_started",
          ),
        );
        recordTokenMetrics(providerLabel, resultUsage);
        streamDuration
          .labels(providerLabel, "success")
          .observe((performance.now() - startedAt) / 1_000);
        usage.resolve(resultUsage);
        queue.close();
      })
      .catch((error: unknown) => {
        producerSettled = true;
        const normalized = normalizeGatewayError(
          error,
          linked.controller.signal,
          providerStarted.settled,
        );
        providerStarted.reject(normalized);
        usage.reject(normalized);
        streamDuration
          .labels(providerLabel, normalized.code === "request_aborted" ? "aborted" : "error")
          .observe((performance.now() - startedAt) / 1_000);
        queue.fail(normalized);
      })
      .finally(linked.cleanup);

    const textStream = (async function* () {
      try {
        yield* queue.values();
      } finally {
        if (!producerSettled && !linked.controller.signal.aborted) {
          linked.controller.abort(
            new ProviderGatewayError(
              "request_aborted",
              "ProviderGateway response consumer stopped reading",
              false,
              providerStarted.settled,
            ),
          );
        }
      }
    })();

    return {
      textStream,
      usage: usage.promise,
      providerStarted: providerStarted.promise,
    };
  }

  private async runExecution(
    input: ProviderGatewayExecutionRequest,
    signal: AbortSignal,
    callbacks: {
      onProviderStarted: () => void;
      onText: (delta: string) => Promise<void>;
      onProviderContent: () => void;
    },
  ) {
    const provider = gatewayProvider(input);
    const tools = await gatewayTools(input.tools);
    const messages = gatewayMessages(input.messages);
    const usage = emptyUsage();
    const deadlineAt = Date.now() + this.deadlineMs;
    const executionId = randomUUID();

    for (let step = 0; step < PROVIDER_GATEWAY_MAX_MODEL_STEPS; step += 1) {
      throwIfAborted(signal);
      const requestId = `${executionId}-${step}`;
      const remainingMs = Math.max(1_000, deadlineAt - Date.now());
      const request = ProviderStreamRequestSchema.parse({
        contractVersion: PROVIDER_CONTRACT_VERSION,
        requestId,
        provider,
        modelId: input.modelId,
        messages,
        ...(tools?.length ? { tools, toolChoice: "auto" as const } : {}),
        ...defined("options", gatewayOptions(input.options)),
        timeoutMs: Math.min(remainingMs, PROVIDER_GATEWAY_MAX_DEADLINE_MS),
      });
      const stepResult = await this.executeStep(request, signal, {
        onProviderStarted: callbacks.onProviderStarted,
        onText: callbacks.onText,
        onProviderContent: callbacks.onProviderContent,
      });
      addUsage(usage, stepResult.usage);
      if (stepResult.toolCalls.length === 0) return finalizeUsage(usage);
      if (step === PROVIDER_GATEWAY_MAX_MODEL_STEPS - 1) {
        throw new ProviderGatewayError(
          "tool_step_limit",
          `Provider requested more than ${PROVIDER_GATEWAY_MAX_MODEL_STEPS} model steps`,
          false,
          true,
        );
      }

      const assistantContent: GatewayAssistantContent = stepResult.toolCalls.map((call) => ({
        type: "tool-call" as const,
        toolCallId: call.toolCallId,
        toolName: call.toolName,
        input: toGatewayJson(call.input),
      }));
      if (stepResult.text) {
        assistantContent.unshift({ type: "text", text: stepResult.text });
      }
      messages.push({ role: "assistant", content: assistantContent });
      const toolResults: GatewayToolContent = [];
      for (const call of stepResult.toolCalls) {
        toolResults.push(await executeBackendTool(call, input.tools, input.messages, signal));
      }
      messages.push({ role: "tool", content: toolResults });
    }
    throw new ProviderGatewayError(
      "tool_step_limit",
      "Provider tool loop did not terminate",
      false,
      true,
    );
  }

  private async executeStep(
    request: ProviderStreamRequest,
    signal: AbortSignal,
    callbacks: {
      onProviderStarted: () => void;
      onText: (delta: string) => Promise<void>;
      onProviderContent: () => void;
    },
  ) {
    const providerLabel = request.provider.kind;
    const body = JSON.stringify(request);
    if (Buffer.byteLength(body) > PROVIDER_GATEWAY_REQUEST_LIMIT_BYTES) {
      throw new ProviderGatewayError(
        "gateway_request_too_large",
        "ProviderGateway request exceeds the 32 MiB internal contract limit",
      );
    }
    if (!this.sharedSecret?.trim()) {
      throw new ProviderGatewayError(
        "gateway_not_configured",
        "ProviderGateway shared secret is not configured",
      );
    }

    let response: Response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}${PROVIDER_STREAM_PATH}`, {
        method: "POST",
        headers: {
          accept: "text/event-stream",
          authorization: `Bearer ${this.sharedSecret}`,
          "content-type": "application/json",
          "x-request-id": request.requestId,
        },
        body,
        signal,
      });
    } catch (error) {
      providerRequestsTotal.labels(providerLabel, signal.aborted ? "aborted" : "error").inc();
      throw normalizeGatewayError(error, signal);
    }

    if (!response.ok) {
      providerRequestsTotal.labels(providerLabel, "error").inc();
      throw await gatewayHttpError(response);
    }
    if (!response.body) {
      providerRequestsTotal.labels(providerLabel, "error").inc();
      throw new ProviderGatewayError(
        "invalid_gateway_response",
        "ProviderGateway response has no body",
      );
    }
    const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
    if (!contentType.startsWith("text/event-stream")) {
      await response.body.cancel().catch(() => undefined);
      providerRequestsTotal.labels(providerLabel, "error").inc();
      throw new ProviderGatewayError(
        "invalid_gateway_response",
        "ProviderGateway response is not an SSE stream",
      );
    }

    let expectedSequence = 0;
    let started = false;
    let done = false;
    let text = "";
    let stepUsage = emptyUsage();
    const toolCalls: Array<{ toolCallId: string; toolName: string; input: unknown }> = [];
    try {
      await parseSse(response.body, async (eventName, rawEvent) => {
        let event: ProviderStreamEvent;
        try {
          event = parseProviderStreamEvent(rawEvent);
        } catch (error) {
          throw new ProviderGatewayError(
            "invalid_gateway_event",
            "ProviderGateway emitted an event that violates contract version 1",
            false,
            started,
            { cause: error },
          );
        }
        if (eventName && eventName !== event.type) {
          throw new ProviderGatewayError(
            "invalid_gateway_event",
            "ProviderGateway SSE event name does not match its payload",
            false,
            started,
          );
        }
        if (event.requestId !== request.requestId) {
          throw new ProviderGatewayError(
            "invalid_gateway_event",
            "ProviderGateway event requestId does not match the request",
            false,
            started,
          );
        }
        if (event.sequence !== expectedSequence) {
          throw new ProviderGatewayError(
            "invalid_gateway_sequence",
            `ProviderGateway sequence ${event.sequence} arrived while ${expectedSequence} was expected`,
            false,
            started,
          );
        }
        expectedSequence += 1;
        if (done) {
          throw new ProviderGatewayError(
            "invalid_gateway_event",
            "ProviderGateway emitted data after done",
            false,
            started,
          );
        }
        if (event.type === "provider_started") {
          if (started || event.sequence !== 0) {
            throw new ProviderGatewayError(
              "invalid_gateway_event",
              "ProviderGateway emitted provider_started out of order",
              false,
              started,
            );
          }
          if (event.provider !== request.provider.kind || event.modelId !== request.modelId) {
            throw new ProviderGatewayError(
              "invalid_gateway_event",
              "ProviderGateway provider_started does not match the request",
            );
          }
          started = true;
          callbacks.onProviderStarted();
          return;
        }
        if (!started) {
          throw new ProviderGatewayError(
            "missing_provider_started",
            "ProviderGateway emitted content before provider_started",
          );
        }
        if (event.type === "text_delta") {
          callbacks.onProviderContent();
          text += event.delta;
          await callbacks.onText(event.delta);
          return;
        }
        if (event.type === "reasoning_delta") {
          callbacks.onProviderContent();
          return;
        }
        if (event.type === "tool_call") {
          callbacks.onProviderContent();
          if (toolCalls.some((call) => call.toolCallId === event.toolCallId)) {
            throw new ProviderGatewayError(
              "invalid_gateway_event",
              "ProviderGateway emitted a duplicate tool call id",
              false,
              true,
            );
          }
          toolCalls.push({
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            input: event.input,
          });
          return;
        }
        if (event.type === "usage") {
          stepUsage = usageFromEvent(event);
          return;
        }
        if (event.type === "error") {
          throw new ProviderGatewayError(event.code, event.message, event.retryable, started);
        }
        if (event.type === "done") done = true;
      });
      if (!done) {
        throw new ProviderGatewayError(
          "missing_done",
          "ProviderGateway stream ended without done",
          false,
          started,
        );
      }
      providerRequestsTotal.labels(providerLabel, "success").inc();
      return { text, toolCalls, usage: stepUsage };
    } catch (error) {
      providerRequestsTotal.labels(providerLabel, signal.aborted ? "aborted" : "error").inc();
      throw normalizeGatewayError(error, signal, started);
    }
  }
}

export function shouldUseProviderGateway(
  input: ProviderGatewayExecutionRequest,
  enabledProviders: ReadonlySet<string> = env.providerGatewayProviders,
) {
  if (enabledProviders.size === 0) return false;
  const provider = String(input.provider).trim().toLowerCase();
  const route = gatewayRouteKey(input);
  return (
    enabledProviders.has("all") || enabledProviders.has(provider) || enabledProviders.has(route)
  );
}

export function gatewayRouteKey(input: ProviderGatewayExecutionRequest) {
  const provider = String(input.provider).trim().toLowerCase();
  if (input.credential.connection?.authMethod === "SUBSCRIPTION_CLI") {
    if (provider === "openai") return "codex";
    if (provider === "xai") return "grok-cli";
  }
  if (provider === "openai") return "openai";
  if (provider === "google") return "google";
  if (provider === "claude" || provider === "anthropic") return "anthropic";
  if (provider === "openrouter") return "openrouter";
  return "openai-compatible";
}

export function gatewayProvider(input: ProviderGatewayExecutionRequest): GatewayProvider {
  const provider = String(input.provider).trim().toLowerCase();
  const connection = input.credential.connection;
  const route = gatewayRouteKey(input);
  if (route === "codex") {
    const codexHome = connection?.codexHome?.trim();
    if (!codexHome)
      throw credentialError("Codex connection is missing its isolated home directory");
    return { kind: "codex", codexHome };
  }
  if (route === "grok-cli") {
    const cliHomeDir = connection?.cliHomeDir?.trim();
    if (!cliHomeDir)
      throw credentialError("Grok connection is missing its isolated home directory");
    return { kind: "grok-cli", cliHomeDir };
  }
  if (route === "openai") return { kind: "openai", apiKey: requireApiKey(input, "OpenAI") };
  if (route === "anthropic") {
    return { kind: "anthropic", apiKey: requireApiKey(input, "Anthropic") };
  }
  if (route === "google") {
    const accessToken = connection?.accessToken?.trim();
    return {
      kind: "google",
      credential: accessToken
        ? {
            type: "oauth",
            accessToken,
            ...defined("quotaProjectId", connection?.quotaProjectId?.trim() || undefined),
          }
        : { type: "api-key", apiKey: requireApiKey(input, "Google") },
    };
  }
  if (route === "openrouter") {
    return {
      kind: "openrouter",
      apiKey: requireApiKey(input, "OpenRouter"),
      appName: env.OPENROUTER_APP_NAME.trim(),
      referrer: env.OPENROUTER_REFERRER?.trim() || env.APP_FRONTEND_URL,
    };
  }
  const baseUrl = connection?.baseUrl?.trim();
  if (!baseUrl) throw credentialError(`Provider ${provider} has no OpenAI-compatible endpoint`);
  return {
    kind: "openai-compatible",
    providerId: provider,
    baseUrl,
    ...defined(
      "apiKey",
      input.credential.apiKey?.trim() || connection?.apiKey?.trim() || undefined,
    ),
    ...defined("headers", nonBlankStringMap(connection?.headers)),
    ...defined("queryParams", nonBlankStringMap(connection?.queryParams)),
  };
}

export function gatewayMessages(messages: ModelMessage[]): GatewayMessage[] {
  return messages.map((message) => {
    if (message.role === "system") {
      return { role: "system", content: requireText(message.content, "system message") };
    }
    if (message.role === "user") {
      if (typeof message.content === "string") {
        return { role: "user", content: requireText(message.content, "user message") };
      }
      const content = message.content.map((part) => {
        if (part.type === "text") {
          return { type: "text" as const, text: requireText(part.text, "user text") };
        }
        if (part.type === "image") {
          return {
            type: "image" as const,
            data: inlineBase64(part.image),
            mediaType: part.mediaType ?? "image/png",
            ...defined("filename", "filename" in part ? part.filename : undefined),
          };
        }
        return {
          type: "file" as const,
          data: inlineBase64(part.data),
          mediaType: part.mediaType,
          ...defined("filename", part.filename),
        };
      });
      return { role: "user", content };
    }
    if (message.role === "assistant") {
      if (typeof message.content === "string") {
        return { role: "assistant", content: requireText(message.content, "assistant message") };
      }
      const content: GatewayAssistantContent = [];
      for (const part of message.content) {
        if (part.type === "text") {
          if (part.text) content.push({ type: "text", text: part.text });
          continue;
        }
        if (part.type === "tool-call") {
          content.push({
            type: "tool-call",
            toolCallId: part.toolCallId,
            toolName: part.toolName,
            input: toGatewayJson(part.input),
          });
        }
      }
      if (content.length === 0)
        throw contractError("Assistant message has no transferable content");
      return { role: "assistant", content };
    }
    const content: GatewayToolContent = [];
    for (const part of message.content) {
      if (part.type !== "tool-result") {
        throw contractError("Tool approval responses are not supported by ProviderGateway");
      }
      content.push({
        type: "tool-result",
        toolCallId: part.toolCallId,
        toolName: part.toolName,
        output: toolModelOutput(part.output),
      });
    }
    if (content.length === 0) throw contractError("Tool message has no results");
    return { role: "tool", content };
  });
}

async function gatewayTools(tools: ToolSet | undefined): Promise<GatewayTool[] | undefined> {
  if (!tools || Object.keys(tools).length === 0) return undefined;
  const result: GatewayTool[] = [];
  for (const [name, definition] of Object.entries(tools)) {
    const schema = asSchema(definition.inputSchema);
    const inputSchema = await schema.jsonSchema;
    if (!inputSchema || typeof inputSchema !== "object" || Array.isArray(inputSchema)) {
      throw contractError(`Tool ${name} does not expose an object JSON schema`);
    }
    result.push({
      name,
      ...defined("description", definition.description?.trim() || undefined),
      inputSchema: inputSchema as Record<string, unknown>,
    });
  }
  return result;
}

async function executeBackendTool(
  call: { toolCallId: string; toolName: string; input: unknown },
  tools: ToolSet | undefined,
  messages: ModelMessage[],
  signal: AbortSignal,
) {
  const definition = tools?.[call.toolName];
  if (!definition) {
    throw new ProviderGatewayError(
      "tool_not_allowed",
      `Provider requested unavailable tool ${call.toolName}`,
      false,
      true,
    );
  }
  const schema = asSchema(definition.inputSchema);
  const validation = schema.validate
    ? await schema.validate(call.input)
    : ({ success: true, value: call.input } as const);
  if (!validation.success) {
    throw new ProviderGatewayError(
      "invalid_tool_arguments",
      `Provider supplied invalid arguments for tool ${call.toolName}`,
      false,
      true,
      { cause: validation.error },
    );
  }
  const executionOptions = {
    toolCallId: call.toolCallId,
    messages,
    abortSignal: signal,
  };
  const needsApproval =
    typeof definition.needsApproval === "function"
      ? await definition.needsApproval(validation.value, executionOptions)
      : definition.needsApproval === true;
  if (needsApproval) {
    throw new ProviderGatewayError(
      "tool_approval_required",
      `Tool ${call.toolName} requires approval for this call`,
      false,
      true,
    );
  }
  const execute = definition.execute as
    | ((input: unknown, options: typeof executionOptions) => unknown)
    | undefined;
  if (!execute) {
    throw new ProviderGatewayError(
      "tool_not_executable",
      `Tool ${call.toolName} cannot be executed by the backend`,
      false,
      true,
    );
  }
  throwIfAborted(signal);
  let output: unknown;
  try {
    const rawOutput = await execute(validation.value, executionOptions);
    output = isAsyncIterable(rawOutput) ? await lastAsyncValue(rawOutput) : rawOutput;
  } catch (error) {
    throw new ProviderGatewayError(
      "tool_execution_failed",
      `Backend tool ${call.toolName} failed`,
      false,
      true,
      { cause: error },
    );
  }
  return {
    type: "tool-result" as const,
    toolCallId: call.toolCallId,
    toolName: call.toolName,
    output: toGatewayJson(output),
  };
}

async function parseSse(
  body: ReadableStream<Uint8Array>,
  onEvent: (eventName: string | undefined, value: unknown) => Promise<void>,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = await drainSseFrames(buffer, onEvent);
      if (Buffer.byteLength(buffer) > PROVIDER_GATEWAY_EVENT_LIMIT_BYTES) {
        throw new ProviderGatewayError(
          "gateway_event_too_large",
          "ProviderGateway emitted an oversized SSE event",
        );
      }
    }
    buffer += decoder.decode();
    buffer = await drainSseFrames(buffer, onEvent);
    if (buffer.trim()) await dispatchSseFrame(buffer, onEvent);
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

async function drainSseFrames(
  input: string,
  onEvent: (eventName: string | undefined, value: unknown) => Promise<void>,
) {
  let buffer = input;
  while (true) {
    const match = /\r?\n\r?\n/.exec(buffer);
    if (!match || match.index == null) return buffer;
    const frame = buffer.slice(0, match.index);
    buffer = buffer.slice(match.index + match[0].length);
    if (frame.trim()) await dispatchSseFrame(frame, onEvent);
  }
}

async function dispatchSseFrame(
  frame: string,
  onEvent: (eventName: string | undefined, value: unknown) => Promise<void>,
) {
  let eventName: string | undefined;
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(":")) continue;
    const separator = line.indexOf(":");
    const field = separator < 0 ? line : line.slice(0, separator);
    const rawValue = separator < 0 ? "" : line.slice(separator + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    if (field === "event") eventName = value;
    if (field === "data") data.push(value);
  }
  if (data.length === 0) return;
  let parsed: unknown;
  try {
    parsed = JSON.parse(data.join("\n"));
  } catch (error) {
    throw new ProviderGatewayError(
      "malformed_gateway_sse",
      "ProviderGateway emitted malformed SSE JSON",
      false,
      false,
      { cause: error },
    );
  }
  await onEvent(eventName, parsed);
}

async function gatewayHttpError(response: Response) {
  const body = await readLimitedText(response, PROVIDER_GATEWAY_ERROR_LIMIT_BYTES);
  try {
    const parsed = JSON.parse(body) as {
      error?: { code?: unknown; message?: unknown; retryable?: unknown };
    };
    const code = typeof parsed.error?.code === "string" ? parsed.error.code : "gateway_http_error";
    const message =
      typeof parsed.error?.message === "string"
        ? parsed.error.message
        : `ProviderGateway returned HTTP ${response.status}`;
    return new ProviderGatewayError(code, message, parsed.error?.retryable === true);
  } catch {
    return new ProviderGatewayError(
      "gateway_http_error",
      `ProviderGateway returned HTTP ${response.status}`,
    );
  }
}

async function readLimitedText(response: Response, maxBytes: number) {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (total <= maxBytes) {
      const { value, done } = await reader.read();
      if (done) break;
      const remaining = maxBytes - total;
      chunks.push(value.subarray(0, Math.max(0, remaining)));
      total += value.byteLength;
      if (total > maxBytes) break;
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
  const bytes = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0));
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

class BoundedAsyncQueue {
  private readonly queued: string[] = [];
  private queuedBytes = 0;
  private closed = false;
  private error: unknown;
  private readonly consumers: Array<() => void> = [];
  private readonly producers: Array<() => void> = [];

  constructor(
    private readonly maxChunks: number,
    private readonly maxBytes: number,
  ) {}

  async push(value: string, signal: AbortSignal) {
    throwIfAborted(signal);
    const bytes = Buffer.byteLength(value);
    while (
      !this.closed &&
      !this.error &&
      (this.queued.length >= this.maxChunks ||
        (this.queued.length > 0 && this.queuedBytes + bytes > this.maxBytes))
    ) {
      await this.waitForProducer(signal);
      throwIfAborted(signal);
    }
    if (this.error) throw this.error;
    if (this.closed) throw new ProviderGatewayError("request_aborted", "Text stream is closed");
    throwIfAborted(signal);
    this.queued.push(value);
    this.queuedBytes += bytes;
    this.consumers.shift()?.();
  }

  close() {
    this.closed = true;
    this.wakeAll();
  }

  fail(error: unknown) {
    this.error = error;
    this.closed = true;
    this.wakeAll();
  }

  async *values() {
    while (true) {
      const value = this.queued.shift();
      if (value !== undefined) {
        this.queuedBytes -= Buffer.byteLength(value);
        this.producers.shift()?.();
        yield value;
        continue;
      }
      if (this.error) throw this.error;
      if (this.closed) return;
      await new Promise<void>((resolve) => this.consumers.push(resolve));
    }
  }

  private wakeAll() {
    for (const wake of this.consumers.splice(0)) wake();
    for (const wake of this.producers.splice(0)) wake();
  }

  private waitForProducer(signal: AbortSignal) {
    return new Promise<void>((resolve) => {
      let settled = false;
      const wake = () => {
        if (settled) return;
        settled = true;
        signal.removeEventListener("abort", wake);
        const index = this.producers.indexOf(wake);
        if (index >= 0) this.producers.splice(index, 1);
        resolve();
      };
      this.producers.push(wake);
      if (signal.aborted) wake();
      else signal.addEventListener("abort", wake, { once: true });
    });
  }
}

function gatewayOptions(options: ProviderGatewayRuntimeOptions | null | undefined) {
  const result = {
    ...defined("temperature", finiteNumber(options?.temperature)),
    ...defined("topP", finiteNumber(options?.topP)),
    ...defined("topK", positiveIntegerOrUndefined(options?.topK)),
    ...defined("maxOutputTokens", positiveIntegerOrUndefined(options?.maxTokens)),
    ...defined("frequencyPenalty", finiteNumber(options?.frequencyPenalty)),
    ...defined("presencePenalty", finiteNumber(options?.presencePenalty)),
    ...defined(
      "stopSequences",
      options?.stopSequences?.map((item) => item.trim()).filter(Boolean) || undefined,
    ),
  };
  return Object.keys(result).length ? result : undefined;
}

function usageFromEvent(event: Extract<ProviderStreamEvent, { type: "usage" }>) {
  return {
    inputTokens: event.inputTokens ?? null,
    outputTokens: event.outputTokens ?? null,
    totalTokens: event.totalTokens ?? null,
    reasoningTokens: event.reasoningTokens ?? null,
    cachedInputTokens: event.cachedInputTokens ?? null,
  };
}

function emptyUsage() {
  return {
    inputTokens: null as number | null,
    outputTokens: null as number | null,
    totalTokens: null as number | null,
    reasoningTokens: null as number | null,
    cachedInputTokens: null as number | null,
  };
}

function addUsage(target: ReturnType<typeof emptyUsage>, value: ReturnType<typeof emptyUsage>) {
  for (const key of Object.keys(target) as Array<keyof typeof target>) {
    if (value[key] != null) target[key] = (target[key] ?? 0) + value[key];
  }
}

function finalizeUsage(value: ReturnType<typeof emptyUsage>): ProviderGatewayUsage {
  return value;
}

function recordTokenMetrics(provider: string, usage: ProviderGatewayUsage) {
  const values = [
    ["input", usage.inputTokens],
    ["output", usage.outputTokens],
    ["reasoning", usage.reasoningTokens],
    ["cached_input", usage.cachedInputTokens],
  ] as const;
  for (const [type, value] of values) {
    if (value != null && value > 0) providerTokensTotal.labels(provider, type).inc(value);
  }
}

function createDeferred<T>() {
  let resolvePromise!: (value: T | PromiseLike<T>) => void;
  let rejectPromise!: (reason?: unknown) => void;
  let settled = false;
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  void promise.catch(() => undefined);
  return {
    promise,
    get settled() {
      return settled;
    },
    resolve(value: T extends void ? undefined : T = undefined as T extends void ? undefined : T) {
      if (settled) return;
      settled = true;
      resolvePromise(value as T);
    },
    reject(reason: unknown) {
      if (settled) return;
      settled = true;
      rejectPromise(reason);
    },
  };
}

function linkedAbortController(source: AbortSignal | undefined, deadlineMs: number) {
  const controller = new AbortController();
  const abortFromSource = () => {
    controller.abort(
      source?.reason ?? new ProviderGatewayError("request_aborted", "Provider request was aborted"),
    );
  };
  if (source?.aborted) abortFromSource();
  else source?.addEventListener("abort", abortFromSource, { once: true });
  const timer = setTimeout(() => {
    controller.abort(
      new ProviderGatewayError("provider_timeout", "Provider request exceeded 30 minutes", true),
    );
  }, deadlineMs);
  timer.unref?.();
  return {
    controller,
    cleanup: () => {
      clearTimeout(timer);
      source?.removeEventListener("abort", abortFromSource);
    },
  };
}

function normalizeGatewayError(error: unknown, signal: AbortSignal, providerStarted = false) {
  if (error instanceof ProviderGatewayError) {
    return providerStarted && !error.providerStarted
      ? new ProviderGatewayError(error.code, error.message, error.retryable, true, { cause: error })
      : error;
  }
  if (signal.aborted) {
    if (signal.reason instanceof ProviderGatewayError) return signal.reason;
    return new ProviderGatewayError(
      "request_aborted",
      "Provider request was aborted",
      false,
      providerStarted,
      { cause: error },
    );
  }
  if (error instanceof Error && error.name === "ZodError") {
    return new ProviderGatewayError(
      "invalid_gateway_contract",
      "ProviderGateway request does not satisfy contract version 1",
      false,
      providerStarted,
      { cause: error },
    );
  }
  return new ProviderGatewayError(
    "gateway_unavailable",
    error instanceof Error ? error.message : "ProviderGateway request failed",
    true,
    providerStarted,
    { cause: error },
  );
}

function toolModelOutput(output: unknown): GatewayJsonValue {
  if (!output || typeof output !== "object") return toGatewayJson(output);
  const record = output as Record<string, unknown>;
  if (
    (record.type === "text" || record.type === "error-text") &&
    typeof record.value === "string"
  ) {
    return record.value;
  }
  if ((record.type === "json" || record.type === "error-json") && "value" in record) {
    return toGatewayJson(record.value);
  }
  return toGatewayJson(output);
}

function toGatewayJson(value: unknown): GatewayJsonValue {
  if (value === undefined) return null;
  try {
    const serialized = JSON.stringify(value);
    if (serialized === undefined) return null;
    return JSON.parse(serialized) as GatewayJsonValue;
  } catch (error) {
    throw contractError("Tool data is not JSON serializable", error);
  }
}

function inlineBase64(value: unknown) {
  if (typeof value === "string") {
    const dataUrl = /^data:[^,;]+(?:;[^,;]+)*;base64,(.+)$/is.exec(value.trim());
    const data = dataUrl?.[1] ?? value.trim();
    if (/^https?:\/\//i.test(data)) {
      throw contractError("Remote media URLs are not allowed in ProviderGateway requests");
    }
    return data;
  }
  if (value instanceof ArrayBuffer) return Buffer.from(value).toString("base64");
  if (ArrayBuffer.isView(value)) {
    return Buffer.from(value.buffer, value.byteOffset, value.byteLength).toString("base64");
  }
  if (value instanceof URL) {
    throw contractError("Remote media URLs are not allowed in ProviderGateway requests");
  }
  throw contractError("Unsupported binary media content");
}

function requireApiKey(input: ProviderGatewayExecutionRequest, provider: string) {
  const value = input.credential.apiKey?.trim() || input.credential.connection?.apiKey?.trim();
  if (!value) throw credentialError(`${provider} credential is missing its API key`);
  return value;
}

function requireText(value: unknown, label: string) {
  if (typeof value !== "string" || !value.trim()) throw contractError(`${label} must not be blank`);
  return value;
}

function nonBlankStringMap(value: Record<string, string> | undefined) {
  if (!value) return undefined;
  const entries = Object.entries(value)
    .map(([key, item]) => [key.trim(), item.trim()] as const)
    .filter(([key, item]) => key && item);
  return entries.length ? Object.fromEntries(entries) : undefined;
}

function contractError(message: string, cause?: unknown) {
  return new ProviderGatewayError("invalid_gateway_contract", message, false, false, { cause });
}

function credentialError(message: string) {
  return new ProviderGatewayError("gateway_credential_invalid", message);
}

function safeErrorMessage(value: string) {
  return value
    .replace(/[\r\n\t]+/g, " ")
    .replace(/(bearer|api[_ -]?key|token|secret)\s*[:=]\s*[^\s,;]+/gi, "$1=[redacted]")
    .trim()
    .slice(0, 1_024);
}

function clampDeadline(value: number) {
  return Math.max(1_000, Math.min(PROVIDER_GATEWAY_MAX_DEADLINE_MS, Math.floor(value)));
}

function positiveInteger(value: number | undefined, fallback: number) {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : fallback;
}

function positiveIntegerOrUndefined(value: unknown) {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
}

function finiteNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function defined<K extends string, V>(key: K, value: V | undefined) {
  return value === undefined ? {} : ({ [key]: value } as Record<K, V>);
}

function throwIfAborted(signal: AbortSignal) {
  if (!signal.aborted) return;
  throw normalizeGatewayError(signal.reason, signal);
}

function isAsyncIterable(value: unknown): value is AsyncIterable<unknown> {
  return Boolean(
    value &&
      (typeof value === "object" || typeof value === "function") &&
      Symbol.asyncIterator in value,
  );
}

async function lastAsyncValue(iterable: AsyncIterable<unknown>) {
  let result: unknown = null;
  for await (const value of iterable) result = value;
  return result;
}
