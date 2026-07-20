import { type ChildProcess, spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { createAnthropic } from "@ai-sdk/anthropic";
import { createGoogleGenerativeAI } from "@ai-sdk/google";
import { createOpenAI } from "@ai-sdk/openai";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import type { ProviderStreamRequest } from "@chat-sribiddi/provider-contract";
import { jsonSchema, type ModelMessage, streamText, type ToolSet } from "ai";
import type { GatewayConfig } from "./config.js";
import { GatewayError, sanitizeErrorMessage } from "./errors.js";

export type ProviderUsage = {
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  reasoningTokens?: number | null;
  cachedInputTokens?: number | null;
};

export type ProviderSink = {
  text(delta: string): Promise<void>;
  reasoning(delta: string): Promise<void>;
  toolCall(toolCallId: string, toolName: string, input: unknown): Promise<void>;
  usage(usage: ProviderUsage): Promise<void>;
};

export type ProviderExecutionResult = { finishReason?: string };

export class ProcessRegistry {
  private readonly children = new Set<ChildProcess>();

  track(child: ChildProcess) {
    this.children.add(child);
    child.once("close", () => this.children.delete(child));
  }

  terminateAll(graceMs: number) {
    for (const child of this.children) terminateChild(child, graceMs);
  }
}

export async function executeProvider(
  request: ProviderStreamRequest,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
  processes: ProcessRegistry,
): Promise<ProviderExecutionResult> {
  throwIfAborted(signal);
  if (request.provider.kind === "codex") {
    return executeCodex(request, sink, signal, config, processes);
  }
  if (request.provider.kind === "grok-cli") {
    return executeGrokCli(request, sink, signal, config, processes);
  }
  return executeSdkProvider(request, sink, signal, config);
}

async function executeSdkProvider(
  request: ProviderStreamRequest,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
) {
  const result = streamText({
    model: languageModel(request, config),
    messages: toModelMessages(request.messages),
    abortSignal: signal,
    maxRetries: 0,
    ...runtimeOptions(request),
    ...toolOptions(request),
  });

  let finishReason: string | undefined;
  for await (const rawPart of result.fullStream) {
    const part = rawPart as unknown as Record<string, unknown>;
    switch (part.type) {
      case "text-delta": {
        const delta = rawTextValue(part.text ?? part.textDelta);
        if (delta) await sink.text(delta);
        break;
      }
      case "reasoning-delta": {
        const delta = rawTextValue(part.text ?? part.textDelta);
        if (delta) await sink.reasoning(delta);
        break;
      }
      case "tool-call": {
        const toolCallId = textValue(part.toolCallId) ?? "unknown-tool-call";
        const toolName = textValue(part.toolName) ?? "unknown-tool";
        await sink.toolCall(toolCallId, toolName, part.input ?? part.args ?? {});
        break;
      }
      case "finish":
        finishReason = textValue(part.finishReason) ?? finishReason;
        break;
      case "error":
        throw part.error ?? new Error("Provider stream failed");
      default:
        break;
    }
  }

  const usage = normalizeUsage(await result.usage);
  await sink.usage(usage);
  const terminalFinishReason = textValue(await result.finishReason);
  if (!finishReason && terminalFinishReason) finishReason = terminalFinishReason;
  return { finishReason };
}

function languageModel(request: ProviderStreamRequest, config: GatewayConfig) {
  const provider = request.provider;
  switch (provider.kind) {
    case "openai":
      return createOpenAI({ apiKey: provider.apiKey })(request.modelId);
    case "anthropic":
      return createAnthropic({ apiKey: provider.apiKey })(request.modelId);
    case "google":
      if (provider.credential.type === "api-key") {
        return createGoogleGenerativeAI({
          apiKey: provider.credential.apiKey,
          baseURL: config.googleBaseUrl,
        })(request.modelId);
      }
      return createGoogleGenerativeAI({
        // The Google adapter requires an API-key value while constructing its
        // headers. The fetch boundary below removes that header before sending.
        apiKey: provider.credential.accessToken,
        baseURL: config.googleBaseUrl,
        fetch: googleOAuthFetch(
          provider.credential.accessToken,
          provider.credential.quotaProjectId,
        ),
      })(request.modelId);
    case "openrouter":
      return createOpenAICompatible({
        name: "openrouter",
        baseURL: config.openRouterBaseUrl,
        apiKey: provider.apiKey,
        includeUsage: true,
        headers: {
          "HTTP-Referer": provider.referrer ?? config.openRouterReferrer ?? "https://germinai.de",
          "X-Title": provider.appName ?? config.openRouterAppName,
        },
      }).chatModel(request.modelId);
    case "openai-compatible":
      return createOpenAICompatible({
        name: provider.providerId,
        baseURL: validateOpenAiCompatibleBaseUrl(provider.baseUrl, config.allowPrivateBaseUrls),
        apiKey: provider.apiKey?.trim() || undefined,
        headers: provider.headers,
        queryParams: provider.queryParams,
        includeUsage: true,
      }).chatModel(request.modelId);
    default:
      throw new GatewayError(
        "provider_not_supported",
        `Provider ${provider.kind} is not an SDK provider`,
        400,
      );
  }
}

export function toModelMessages(messages: ProviderStreamRequest["messages"]): ModelMessage[] {
  return messages.map((message) => {
    if (message.role === "system") return message as ModelMessage;
    if (message.role === "user") {
      if (typeof message.content === "string") {
        return { role: "user", content: message.content } as ModelMessage;
      }
      return {
        role: "user",
        content: message.content.map((part) => {
          if (part.type === "text") return part;
          if (part.type === "image") {
            return {
              type: "image",
              image: part.data,
              mediaType: part.mediaType,
              ...(part.filename ? { filename: part.filename } : {}),
            };
          }
          return {
            type: "file",
            data: part.data,
            mediaType: part.mediaType,
            ...(part.filename ? { filename: part.filename } : {}),
          };
        }),
      } as ModelMessage;
    }
    if (message.role === "assistant") {
      if (typeof message.content === "string") {
        return { role: "assistant", content: message.content } as ModelMessage;
      }
      return {
        role: "assistant",
        content: message.content.map((part) =>
          part.type === "text"
            ? part
            : {
                type: "tool-call",
                toolCallId: part.toolCallId,
                toolName: part.toolName,
                input: part.input,
              },
        ),
      } as ModelMessage;
    }
    return {
      role: "tool",
      content: message.content.map((part) => ({
        type: "tool-result",
        toolCallId: part.toolCallId,
        toolName: part.toolName,
        output:
          typeof part.output === "string"
            ? { type: "text", value: part.output }
            : { type: "json", value: part.output },
      })),
    } as ModelMessage;
  });
}

function googleOAuthFetch(accessToken: string, quotaProjectId?: string) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    headers.delete("x-goog-api-key");
    headers.set("authorization", `Bearer ${accessToken}`);
    if (quotaProjectId) headers.set("x-goog-user-project", quotaProjectId);
    return fetch(input, { ...init, headers });
  };
}

function runtimeOptions(request: ProviderStreamRequest) {
  return {
    temperature: request.options?.temperature,
    topP: request.options?.topP,
    topK: request.options?.topK,
    maxOutputTokens: request.options?.maxOutputTokens,
    frequencyPenalty: request.options?.frequencyPenalty,
    presencePenalty: request.options?.presencePenalty,
    stopSequences: request.options?.stopSequences,
  };
}

function toolOptions(request: ProviderStreamRequest): {
  tools?: ToolSet;
  toolChoice?: "auto" | "none" | "required";
} {
  if (!request.tools?.length) return {};
  const tools = Object.fromEntries(
    request.tools.map((item) => [
      item.name,
      {
        description: item.description,
        inputSchema: jsonSchema(item.inputSchema),
      },
    ]),
  ) as ToolSet;
  return { tools, toolChoice: request.toolChoice ?? "auto" };
}

async function executeCodex(
  request: ProviderStreamRequest,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
  processes: ProcessRegistry,
) {
  if (request.provider.kind !== "codex")
    throw new GatewayError("invalid_provider", "Codex provider expected");
  const prompt = formatCliPrompt(request.messages);
  const runDir = await mkdtemp(join(tmpdir(), "chat-sribiddi-provider-gateway-codex-"));
  try {
    const child = spawn(
      config.codexBinary,
      [
        "exec",
        "--json",
        "--ephemeral",
        "--ignore-user-config",
        "--skip-git-repo-check",
        "--sandbox",
        "read-only",
        "-c",
        'approval_policy="never"',
        "-m",
        request.modelId,
        "-C",
        runDir,
        "-",
      ],
      {
        cwd: runDir,
        env: safeCodexEnvironment(request.provider.codexHome),
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    processes.track(child);
    child.stdin.end(prompt);
    const parsed = await consumeCodexProcess(child, sink, signal, config);
    if (parsed.error) {
      throw new GatewayError("provider_reconnect_required", parsed.error, 403, false);
    }
    if (!parsed.emittedText) {
      throw new GatewayError(
        "provider_error",
        "OpenAI Codex returned no assistant message",
        502,
        true,
      );
    }
    if (parsed.usage) await sink.usage(parsed.usage);
    return { finishReason: "stop" };
  } finally {
    await rm(runDir, { recursive: true, force: true });
  }
}

async function consumeCodexProcess(
  child: ChildProcess,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
) {
  if (!child.stdout || !child.stderr) {
    throw new GatewayError(
      "provider_unavailable",
      "Codex process streams are unavailable",
      503,
      true,
    );
  }
  let outputBytes = 0;
  let emittedText = false;
  let providerError: string | null = null;
  let usage: ProviderUsage | null = null;
  let stderrTail = "";
  let sinkQueue = Promise.resolve();
  let fatalError: Error | null = null;
  const lineReader = createInterface({ input: child.stdout });

  const fail = (error: Error) => {
    fatalError ??= error;
    terminateChild(child, config.cliKillGraceMs);
  };
  const countBytes = (value: Buffer | string) => {
    outputBytes += Buffer.byteLength(value);
    if (outputBytes > config.codexOutputLimitBytes) {
      fail(
        new GatewayError(
          "provider_output_too_large",
          "Codex output exceeded the configured limit",
          502,
          false,
        ),
      );
    }
  };
  child.stderr.on("data", (chunk) => {
    countBytes(chunk);
    stderrTail = `${stderrTail}${String(chunk)}`.slice(-4_096);
  });
  lineReader.on("line", (line) => {
    countBytes(line);
    const parsed = parseCodexEvent(line);
    if (parsed.text) {
      emittedText = true;
      sinkQueue = sinkQueue.then(() => sink.text(parsed.text ?? ""));
    }
    if (parsed.reasoning) sinkQueue = sinkQueue.then(() => sink.reasoning(parsed.reasoning ?? ""));
    if (parsed.error) providerError = parsed.error;
    if (parsed.usage) usage = parsed.usage;
  });

  const abort = () => fail(abortError(signal, "Codex request was cancelled"));
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  try {
    const [code, closeSignal] = (await Promise.race([
      once(child, "close"),
      once(child, "error").then(([error]) => {
        throw new GatewayError(
          "provider_unavailable",
          `Codex failed to start: ${error instanceof Error ? error.message : String(error)}`,
          503,
          true,
        );
      }),
    ])) as [number | null, NodeJS.Signals | null];
    await sinkQueue;
    if (fatalError) throw fatalError;
    if (signal.aborted)
      throw new GatewayError("request_aborted", "Codex request was cancelled", 504, true);
    if (code !== 0) {
      throw new GatewayError(
        "provider_error",
        sanitizeErrorMessage(stderrTail || `Codex exited with ${code ?? closeSignal ?? "unknown"}`),
        502,
        true,
      );
    }
    return { emittedText, error: providerError, usage };
  } finally {
    signal.removeEventListener("abort", abort);
    lineReader.close();
  }
}

async function executeGrokCli(
  request: ProviderStreamRequest,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
  processes: ProcessRegistry,
) {
  if (request.provider.kind !== "grok-cli")
    throw new GatewayError("invalid_provider", "Grok provider expected");
  const prompt = formatCliPrompt(request.messages);
  const workspaceDir = await mkdtemp(join(tmpdir(), "chat-sribiddi-provider-gateway-grok-"));
  const child = spawn(
    config.grokBinary,
    [
      "--no-auto-update",
      "--permission-mode",
      "dontAsk",
      "--sandbox",
      "read-only",
      "agent",
      "stdio",
    ],
    {
      cwd: workspaceDir,
      env: safeCliEnvironment(request.provider.cliHomeDir),
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  processes.track(child);
  try {
    await runGrokAcp(child, workspaceDir, prompt, sink, signal, config);
    await sink.usage({
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
    });
    return { finishReason: "stop" };
  } finally {
    terminateChild(child, config.cliKillGraceMs);
    await rm(workspaceDir, { recursive: true, force: true });
  }
}

async function runGrokAcp(
  child: ChildProcess,
  workspaceDir: string,
  prompt: string,
  sink: ProviderSink,
  signal: AbortSignal,
  config: GatewayConfig,
) {
  if (!child.stdout || !child.stderr || !child.stdin) {
    throw new GatewayError(
      "provider_unavailable",
      "Grok CLI process streams are unavailable",
      503,
      true,
    );
  }
  const stdin = child.stdin;
  let nextId = 1;
  let outputBytes = 0;
  let fatalError: Error | null = null;
  let textQueue = Promise.resolve();
  const pending = new Map<
    number,
    {
      resolve: (value: unknown) => void;
      reject: (error: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  const lineReader = createInterface({ input: child.stdout });

  const fail = (error: Error) => {
    if (fatalError) return;
    fatalError = error;
    for (const item of pending.values()) {
      clearTimeout(item.timer);
      item.reject(error);
    }
    pending.clear();
    terminateChild(child, config.cliKillGraceMs);
  };
  const countBytes = (value: Buffer | string) => {
    outputBytes += Buffer.byteLength(value);
    if (outputBytes > config.grokOutputLimitBytes) {
      fail(
        new GatewayError(
          "provider_output_too_large",
          "Grok CLI output exceeded the configured limit",
          502,
        ),
      );
    }
  };

  child.stderr.on("data", countBytes);
  child.once("error", (error) =>
    fail(
      new GatewayError(
        "provider_unavailable",
        `Grok CLI failed to start: ${error.message}`,
        503,
        true,
      ),
    ),
  );
  child.once("exit", (code, exitSignal) => {
    if (pending.size > 0) {
      fail(
        new GatewayError(
          "provider_error",
          `Grok CLI exited before completing the request: ${code ?? exitSignal ?? "unknown"}`,
          502,
          true,
        ),
      );
    }
  });
  lineReader.on("line", (line) => {
    countBytes(line);
    let message: Record<string, unknown>;
    try {
      message = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return;
    }
    const params = asRecord(message.params);
    const update = asRecord(params?.update);
    const content = asRecord(update?.content);
    if (
      message.method === "session/update" &&
      update?.sessionUpdate === "agent_message_chunk" &&
      typeof content?.text === "string" &&
      content.text
    ) {
      textQueue = textQueue.then(() => sink.text(content.text as string));
      return;
    }
    if (typeof message.id !== "number") return;
    const item = pending.get(message.id);
    if (!item) return;
    pending.delete(message.id);
    clearTimeout(item.timer);
    const rpcError = asRecord(message.error);
    if (rpcError)
      item.reject(
        new GatewayError("provider_error", textValue(rpcError.message) ?? "Grok RPC failed", 502),
      );
    else item.resolve(message.result ?? {});
  });

  const abort = () => fail(abortError(signal, "Grok request was cancelled"));
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  const rpc = async (method: string, params: unknown) => {
    if (fatalError) throw fatalError;
    const id = nextId;
    nextId += 1;
    const response = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new GatewayError("provider_timeout", `Grok ${method} timed out`, 504, true));
      }, config.grokRpcTimeoutMs);
      timer.unref?.();
      pending.set(id, { resolve, reject, timer });
    });
    if (!stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`)) {
      await once(stdin, "drain");
    }
    return response;
  };

  try {
    const initialized = asRecord(
      await rpc("initialize", {
        protocolVersion: 1,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      }),
    );
    const methods = Array.isArray(initialized?.authMethods)
      ? new Set(
          initialized.authMethods.map((item) => textValue(asRecord(item)?.id)).filter(Boolean),
        )
      : new Set<string>();
    if (!methods.has("cached_token")) {
      throw new GatewayError(
        "provider_reconnect_required",
        "Run the xAI Grok connection flow before chatting",
        403,
        false,
      );
    }
    await rpc("authenticate", { methodId: "cached_token", _meta: { headless: true } });
    const session = asRecord(await rpc("session/new", { cwd: workspaceDir, mcpServers: [] }));
    const sessionId = textValue(session?.sessionId);
    if (!sessionId)
      throw new GatewayError("provider_error", "Grok ACP did not return a session id", 502);
    await rpc("session/prompt", { sessionId, prompt: [{ type: "text", text: prompt }] });
    await textQueue;
    if (fatalError) throw fatalError;
  } finally {
    signal.removeEventListener("abort", abort);
    lineReader.close();
    for (const item of pending.values()) clearTimeout(item.timer);
    pending.clear();
  }
}

export function validateOpenAiCompatibleBaseUrl(rawBaseUrl: string, allowPrivate = false) {
  let parsed: URL;
  try {
    parsed = new URL(rawBaseUrl);
  } catch {
    throw new GatewayError("invalid_request", "baseUrl must be a valid URL");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new GatewayError("invalid_request", "baseUrl must use http or https");
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new GatewayError(
      "invalid_request",
      "baseUrl must not include credentials, query, or fragment",
    );
  }
  if (!allowPrivate && isPrivateHost(parsed.hostname)) {
    throw new GatewayError(
      "local_endpoint_disabled",
      "Private or local provider endpoints are disabled",
      403,
    );
  }
  return parsed.toString().replace(/\/$/, "");
}

export function parseCodexExecJsonl(output: string) {
  let text = "";
  let reasoning = "";
  let error: string | null = null;
  let usage: ProviderUsage | null = null;
  for (const line of output.split(/\r?\n/)) {
    const parsed = parseCodexEvent(line);
    text += parsed.text ?? "";
    reasoning += parsed.reasoning ?? "";
    error = parsed.error ?? error;
    usage = parsed.usage ?? usage;
  }
  return { text, reasoning, error, usage };
}

function parseCodexEvent(line: string) {
  if (!line.trim()) return {};
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(line) as Record<string, unknown>;
  } catch {
    return {};
  }
  if (event.type === "error" || event.type === "turn.failed") {
    return {
      error: sanitizeErrorMessage(
        textValue(event.message) ?? textValue(event.error) ?? "Codex failed",
      ),
    };
  }
  if (event.type === "turn.completed") return { usage: normalizeUsage(event.usage) };
  if (event.type !== "item.completed") return {};
  const item = asRecord(event.item);
  if (item?.type === "agent_message") return { text: rawTextValue(item.text) ?? "" };
  if (item?.type === "reasoning")
    return { reasoning: rawTextValue(item.text) ?? rawTextValue(item.summary) ?? "" };
  return {};
}

function normalizeUsage(value: unknown): ProviderUsage {
  const source = asRecord(value) ?? {};
  const inputTokens = numberOrNull(source.inputTokens ?? source.input_tokens);
  const outputTokens = numberOrNull(source.outputTokens ?? source.output_tokens);
  return {
    inputTokens,
    outputTokens,
    totalTokens:
      numberOrNull(source.totalTokens ?? source.total_tokens) ??
      (inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null),
    reasoningTokens: numberOrNull(source.reasoningTokens ?? source.reasoning_tokens),
    cachedInputTokens: numberOrNull(source.cachedInputTokens ?? source.cached_input_tokens),
  };
}

function isPrivateHost(hostname: string) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host === "::1") return true;
  if (
    host.startsWith("fc") ||
    host.startsWith("fd") ||
    host.startsWith("fe8") ||
    host.startsWith("fe9")
  )
    return true;
  const parts = host.split(".").map(Number);
  if (
    parts.length !== 4 ||
    !parts.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)
  ) {
    return false;
  }
  return (
    parts[0] === 10 ||
    parts[0] === 127 ||
    (parts[0] === 169 && parts[1] === 254) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168)
  );
}

function terminateChild(child: ChildProcess, graceMs: number) {
  if (child.exitCode != null || child.signalCode != null || child.killed) return;
  child.kill("SIGTERM");
  const timer = setTimeout(() => {
    if (child.exitCode == null && child.signalCode == null) child.kill("SIGKILL");
  }, graceMs);
  timer.unref?.();
}

export function formatCliPrompt(messages: ProviderStreamRequest["messages"]) {
  return messages
    .map((message) => {
      if (typeof message.content === "string") {
        return `${message.role.toUpperCase()}:\n${message.content}`;
      }
      const content = message.content
        .map((part) => {
          if (part.type === "text") return part.text;
          if (part.type === "tool-call") {
            return `[tool-call id=${part.toolCallId} name=${part.toolName}]\n${JSON.stringify(part.input)}`;
          }
          if (part.type === "tool-result") {
            return `[tool-result id=${part.toolCallId} name=${part.toolName}]\n${JSON.stringify(part.output)}`;
          }
          throw new GatewayError(
            "multimodal_cli_unsupported",
            `${message.role} ${part.type} content is not supported by CLI providers`,
            400,
            false,
          );
        })
        .join("\n");
      return `${message.role.toUpperCase()}:\n${content}`;
    })
    .join("\n\n");
}

export function safeCodexEnvironment(
  codexHome: string,
  environment: NodeJS.ProcessEnv = process.env,
): NodeJS.ProcessEnv {
  return {
    ...safeCliEnvironment(codexHome, environment),
    CODEX_HOME: codexHome,
    RUST_LOG: environment.RUST_LOG ?? "error",
  };
}

function safeCliEnvironment(
  homeDir: string,
  environment: NodeJS.ProcessEnv = process.env,
): NodeJS.ProcessEnv {
  const result: NodeJS.ProcessEnv = {
    HOME: homeDir,
    XDG_CONFIG_HOME: join(homeDir, ".config"),
    XDG_CACHE_HOME: join(homeDir, ".cache"),
    XDG_DATA_HOME: join(homeDir, ".local", "share"),
    TMPDIR: tmpdir(),
    PATH: environment.PATH ?? "/usr/bin:/bin",
    LANG: environment.LANG ?? "C.UTF-8",
    LC_ALL: environment.LC_ALL,
    HTTPS_PROXY: safeProxy(environment.HTTPS_PROXY),
    HTTP_PROXY: safeProxy(environment.HTTP_PROXY),
    NO_PROXY: environment.NO_PROXY,
  };
  for (const key of Object.keys(result)) if (result[key] == null) delete result[key];
  return result;
}

function safeProxy(value: string | undefined) {
  if (!value) return undefined;
  try {
    const parsed = new URL(value);
    return parsed.username || parsed.password ? undefined : value;
  } catch {
    return value.includes("@") ? undefined : value;
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function textValue(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function rawTextValue(value: unknown) {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function throwIfAborted(signal: AbortSignal) {
  if (signal.aborted) throw abortError(signal, "Provider request was cancelled");
}

function abortError(signal: AbortSignal, fallbackMessage: string) {
  return signal.reason instanceof Error
    ? signal.reason
    : new GatewayError("request_aborted", fallbackMessage, 504, true);
}

function numberOrNull(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? Math.floor(value)
    : null;
}
