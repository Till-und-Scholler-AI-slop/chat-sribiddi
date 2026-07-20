import { createAnthropic } from "@ai-sdk/anthropic";
import { createGoogleGenerativeAI } from "@ai-sdk/google";
import { createOpenAI } from "@ai-sdk/openai";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { generateText, type ModelMessage, stepCountIs, streamText, type ToolSet } from "ai";
import { env } from "../config/env.js";
import type { Provider } from "../db/types.js";
import { upstreamError } from "../errors/http-error.js";
import {
  providerRequestsTotal,
  providerTokensTotal,
  streamDuration,
  streamTimeToFirstToken,
} from "../observability/metrics.js";
import { fetchWithTimeout } from "../utils/fetch.js";
import { generateCodexExec, streamCodexExec } from "./codex-exec-runtime.js";
import type { ResolvedCredential } from "./credential-access-service.js";
import type { GrokCliBridge, GrokCliSession } from "./grok-cli-bridge-service.js";
import {
  gatewayRouteKey,
  ProviderGatewayClient,
  type ProviderGatewayStreamResult,
  shouldUseProviderGateway,
} from "./provider-gateway-client.js";

const GOOGLE_GENERATE_TIMEOUT_MS = 60_000;

export type RuntimeOptions = {
  temperature?: number | null | undefined;
  topP?: number | null | undefined;
  topK?: number | null | undefined;
  maxTokens?: number | null | undefined;
  frequencyPenalty?: number | null | undefined;
  presencePenalty?: number | null | undefined;
  stopSequences?: string[] | null | undefined;
};

export type ChatExecutionRequest = {
  userId?: string | null | undefined;
  provider: Provider | string;
  modelId: string;
  credential: ResolvedCredential;
  messages: ModelMessage[];
  options?: RuntimeOptions | null;
  tools?: ToolSet | undefined;
  abortSignal?: AbortSignal | undefined;
};

export type ChatStreamResult = ProviderGatewayStreamResult;

export class AiChatService {
  constructor(
    private readonly grokCli?: GrokCliBridge | undefined,
    private readonly gateway: Pick<ProviderGatewayClient, "stream"> = new ProviderGatewayClient(),
    private readonly gatewayProviders: ReadonlySet<string> = env.providerGatewayProviders,
  ) {}

  stream(input: ChatExecutionRequest): ChatStreamResult {
    if (shouldUseProviderGateway(input, this.gatewayProviders)) {
      return this.gateway.stream(input);
    }
    const abortScope = directAbortScope(input.abortSignal);
    const directInput = { ...input, abortSignal: abortScope.signal };
    try {
      if (isOpenAiCodexSubscription(directInput)) {
        return instrumentDirectStream(
          directInput,
          streamCodexExec(directInput),
          abortScope.cleanup,
        );
      }
      if (isGrokCliSubscription(directInput)) {
        return instrumentDirectStream(
          directInput,
          streamGrokCliSubscription(directInput, this.grokCli),
          abortScope.cleanup,
        );
      }
      if (isGoogleOAuth(directInput)) {
        return instrumentDirectStream(
          directInput,
          streamGoogleOAuth(directInput),
          abortScope.cleanup,
        );
      }
      return instrumentDirectStream(
        directInput,
        streamText({
          model: languageModel(directInput),
          messages: directInput.messages,
          abortSignal: abortScope.signal,
          maxRetries: 0,
          ...toolSettings(directInput.tools),
          ...settings(directInput.options),
        }),
        abortScope.cleanup,
      );
    } catch (error) {
      abortScope.cleanup();
      throw error;
    }
  }

  async generate(input: ChatExecutionRequest) {
    if (shouldUseProviderGateway(input, this.gatewayProviders)) {
      const result = this.gateway.stream(input);
      await result.providerStarted;
      let text = "";
      for await (const chunk of result.textStream) text += chunk;
      return { text, usage: await result.usage };
    }
    const abortScope = directAbortScope(input.abortSignal);
    const directInput = { ...input, abortSignal: abortScope.signal };
    const provider = gatewayRouteKey(directInput);
    try {
      const operation: Promise<{ text: string; usage: unknown }> = (async () => {
        if (isOpenAiCodexSubscription(directInput)) return generateCodexExec(directInput);
        if (isGrokCliSubscription(directInput)) {
          return generateGrokCliSubscription(directInput, this.grokCli);
        }
        if (isGoogleOAuth(directInput)) return generateGoogleOAuth(directInput);
        return generateText({
          model: languageModel(directInput),
          messages: directInput.messages,
          maxRetries: 0,
          abortSignal: abortScope.signal,
          ...toolSettings(directInput.tools),
          ...settings(directInput.options),
        });
      })();
      const result = await abortable(operation, abortScope.signal);
      providerRequestsTotal.labels(provider, "success").inc();
      recordDirectTokenMetrics(provider, result.usage);
      return result;
    } catch (error) {
      providerRequestsTotal.labels(provider, abortScope.signal.aborted ? "aborted" : "error").inc();
      throw error;
    } finally {
      abortScope.cleanup();
    }
  }
}

function instrumentDirectStream(
  input: ChatExecutionRequest,
  result: { textStream: AsyncIterable<string>; usage: PromiseLike<unknown> },
  cleanup: () => void,
): ChatStreamResult {
  const provider = gatewayRouteKey(input);
  const startedAt = performance.now();
  let firstToken = false;
  const usage = abortable(Promise.resolve(result.usage), input.abortSignal).then((value) => {
    recordDirectTokenMetrics(provider, value);
    return normalizeDirectUsage(value);
  });
  void usage.catch(() => undefined);
  const textStream = (async function* () {
    let status = "success";
    const iterator = result.textStream[Symbol.asyncIterator]();
    try {
      while (true) {
        const next = await abortable(iterator.next(), input.abortSignal);
        if (next.done) break;
        const chunk = next.value;
        if (!firstToken) {
          firstToken = true;
          streamTimeToFirstToken.labels(provider).observe((performance.now() - startedAt) / 1_000);
        }
        yield chunk;
      }
    } catch (error) {
      status = input.abortSignal?.aborted ? "aborted" : "error";
      throw error;
    } finally {
      providerRequestsTotal.labels(provider, status).inc();
      streamDuration.labels(provider, status).observe((performance.now() - startedAt) / 1_000);
      cleanup();
      void Promise.resolve(iterator.return?.()).catch(() => undefined);
    }
  })();
  return { textStream, usage, providerStarted: Promise.resolve() };
}

function directAbortScope(source: AbortSignal | undefined) {
  const controller = new AbortController();
  const abortFromSource = () => controller.abort(source?.reason ?? directAbortError());
  if (source?.aborted) abortFromSource();
  else source?.addEventListener("abort", abortFromSource, { once: true });
  const timeoutMs = Math.min(env.CHAT_STREAM_TIMEOUT_MS, 30 * 60_000);
  const timer = setTimeout(() => {
    controller.abort(new Error("Direct provider request exceeded its configured deadline"));
  }, timeoutMs);
  timer.unref?.();
  return {
    signal: controller.signal,
    cleanup() {
      clearTimeout(timer);
      source?.removeEventListener("abort", abortFromSource);
    },
  };
}

function abortable<T>(operation: PromiseLike<T>, signal: AbortSignal | undefined): Promise<T> {
  if (!signal) return Promise.resolve(operation);
  if (signal.aborted) return Promise.reject(abortReason(signal));
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => {
      signal.removeEventListener("abort", onAbort);
      reject(abortReason(signal));
    };
    signal.addEventListener("abort", onAbort, { once: true });
    Promise.resolve(operation).then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve(value);
      },
      (error) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}

function abortReason(signal: AbortSignal) {
  return signal.reason instanceof Error ? signal.reason : directAbortError();
}

function directAbortError() {
  return new Error("Direct provider request was aborted");
}

function normalizeDirectUsage(value: unknown) {
  const usage = (value ?? {}) as {
    inputTokens?: number | null;
    outputTokens?: number | null;
    totalTokens?: number | null;
    promptTokens?: number | null;
    completionTokens?: number | null;
    reasoningTokens?: number | null;
    cachedInputTokens?: number | null;
  };
  const inputTokens = usage.inputTokens ?? usage.promptTokens ?? null;
  const outputTokens = usage.outputTokens ?? usage.completionTokens ?? null;
  return {
    inputTokens,
    outputTokens,
    totalTokens:
      usage.totalTokens ??
      (inputTokens != null || outputTokens != null
        ? (inputTokens ?? 0) + (outputTokens ?? 0)
        : null),
    reasoningTokens: usage.reasoningTokens ?? null,
    cachedInputTokens: usage.cachedInputTokens ?? null,
  };
}

function recordDirectTokenMetrics(provider: string, value: unknown) {
  const usage = normalizeDirectUsage(value);
  const values = [
    ["input", usage.inputTokens],
    ["output", usage.outputTokens],
    ["reasoning", usage.reasoningTokens],
    ["cached_input", usage.cachedInputTokens],
  ] as const;
  for (const [type, tokens] of values) {
    if (tokens != null && tokens > 0) providerTokensTotal.labels(provider, type).inc(tokens);
  }
}

function isOpenAiCodexSubscription(input: ChatExecutionRequest) {
  return (
    (input.provider === "OPENAI" || input.provider === "openai") &&
    input.credential.connection?.authMethod === "SUBSCRIPTION_CLI"
  );
}

function isGrokCliSubscription(input: ChatExecutionRequest) {
  return input.provider === "xai" && input.credential.connection?.authMethod === "SUBSCRIPTION_CLI";
}

async function generateGrokCliSubscription(
  input: ChatExecutionRequest,
  bridge: GrokCliBridge | undefined,
) {
  if (!bridge) throw upstreamError("Grok Build subscription bridge is not configured");
  if (!input.userId?.trim()) throw upstreamError("Grok subscription request is missing user scope");
  const session = grokCliSession(input);
  return bridge.generate({
    userId: input.userId,
    session,
    modelId: input.modelId,
    messages: input.messages,
  });
}

function streamGrokCliSubscription(input: ChatExecutionRequest, bridge: GrokCliBridge | undefined) {
  const result = generateGrokCliSubscription(input, bridge);
  return {
    textStream: (async function* () {
      yield (await result).text;
    })(),
    usage: result.then((value) => value.usage),
  };
}

function grokCliSession(input: ChatExecutionRequest): GrokCliSession {
  const connection = input.credential.connection;
  if (
    !connection?.cliHomeDir?.trim() ||
    !connection.cliSessionId?.trim() ||
    connection.cliBridge !== "grok-build"
  ) {
    throw upstreamError("Grok subscription connection is incomplete");
  }
  return {
    cliHomeDir: connection.cliHomeDir,
    cliSessionId: connection.cliSessionId,
    modelId: connection.modelId?.trim() || input.modelId,
  };
}

function isGoogleOAuth(input: ChatExecutionRequest) {
  return (
    (input.provider === "GOOGLE" || input.provider === "google") &&
    Boolean(input.credential.connection?.accessToken?.trim())
  );
}

async function generateGoogleOAuth(input: ChatExecutionRequest) {
  const response = await postGoogleGenerateContent(input);
  return {
    text: extractGoogleText(response),
    usage: googleUsage(response),
  };
}

function streamGoogleOAuth(input: ChatExecutionRequest) {
  const result = generateGoogleOAuth(input);
  return {
    textStream: (async function* () {
      yield (await result).text;
    })(),
    usage: result.then((value) => value.usage),
  };
}

function toolSettings(tools: ToolSet | undefined) {
  return tools
    ? {
        tools,
        stopWhen: stepCountIs(5),
      }
    : {};
}

function languageModel(input: ChatExecutionRequest) {
  const provider = input.provider;
  const credential = input.credential;
  if (provider === "OPENAI" || provider === "openai") {
    return createOpenAI({ apiKey: requireApiKey(credential, "OpenAI") })(input.modelId);
  }
  if (provider === "GOOGLE" || provider === "google") {
    return createGoogleGenerativeAI({ apiKey: requireApiKey(credential, "Google") })(input.modelId);
  }
  if (provider === "CLAUDE" || provider === "anthropic") {
    return createAnthropic({ apiKey: requireApiKey(credential, "Anthropic") })(input.modelId);
  }
  if (provider === "OPENROUTER" || provider === "openrouter") {
    return createOpenAICompatible({
      name: "openrouter",
      baseURL: env.OPENROUTER_BASE_URL,
      apiKey: requireApiKey(credential, "OpenRouter"),
      headers: {
        "HTTP-Referer": env.OPENROUTER_REFERRER ?? env.APP_FRONTEND_URL,
        "X-Title": env.OPENROUTER_APP_NAME,
      },
      includeUsage: true,
    }).chatModel(input.modelId);
  }

  const connection = credential.connection;
  const baseURL = connection?.baseUrl;
  if (!baseURL) {
    throw upstreamError(`Provider ${provider} has no executable endpoint`);
  }
  return createOpenAICompatible({
    name: String(provider),
    baseURL,
    ...defined("apiKey", credential.apiKey?.trim() || undefined),
    ...defined("headers", connection.headers),
    ...defined("queryParams", connection.queryParams),
    includeUsage: true,
  }).chatModel(input.modelId);
}

async function postGoogleGenerateContent(input: ChatExecutionRequest) {
  const accessToken = input.credential.connection?.accessToken?.trim();
  if (!accessToken) throw upstreamError("Google OAuth access token is missing");
  const response = await fetchWithTimeout(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(input.modelId)}:generateContent`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${accessToken}`,
        ...defined(
          "x-goog-user-project",
          input.credential.connection?.quotaProjectId?.trim() || undefined,
        ),
      },
      body: JSON.stringify(googleRequestBody(input.messages, input.options)),
      timeoutMs: GOOGLE_GENERATE_TIMEOUT_MS,
      ...defined("signal", input.abortSignal),
    },
  );
  if (!response.ok) {
    const body = await response.text();
    throw upstreamError(`Google OAuth request failed with HTTP ${response.status}`, body);
  }
  return (await response.json()) as GoogleGenerateContentResponse;
}

function googleRequestBody(messages: ModelMessage[], options: RuntimeOptions | null | undefined) {
  const systemTexts: string[] = [];
  const contents: Array<{ role: "user" | "model"; parts: GooglePart[] }> = [];
  for (const message of messages) {
    if (message.role === "system") {
      const text = modelMessageText(message);
      if (!text.trim()) continue;
      systemTexts.push(text);
    } else {
      const parts = googleParts(message);
      if (parts.length === 0) continue;
      contents.push({
        role: message.role === "assistant" ? "model" : "user",
        parts,
      });
    }
  }
  const body: Record<string, unknown> = { contents };
  if (systemTexts.length > 0) {
    body.systemInstruction = { parts: [{ text: systemTexts.join("\n\n") }] };
  }
  const generationConfig = {
    ...defined("temperature", numberOrUndefined(options?.temperature)),
    ...defined("topP", numberOrUndefined(options?.topP)),
    ...defined("topK", numberOrUndefined(options?.topK)),
    ...defined("maxOutputTokens", numberOrUndefined(options?.maxTokens)),
    ...defined(
      "stopSequences",
      Array.isArray(options?.stopSequences)
        ? options.stopSequences.filter((item) => item.trim())
        : undefined,
    ),
  };
  if (Object.keys(generationConfig).length > 0) {
    body.generationConfig = generationConfig;
  }
  return body;
}

type GooglePart = { text: string } | { inlineData: { mimeType: string; data: string } };

function googleParts(message: ModelMessage): GooglePart[] {
  const content = message.content;
  if (typeof content === "string") return content.trim() ? [{ text: content }] : [];
  if (!Array.isArray(content)) return [];
  const parts: GooglePart[] = [];
  for (const part of content) {
    if (part.type === "text" && part.text.trim()) {
      parts.push({ text: part.text });
      continue;
    }
    if (part.type === "image") {
      const data = dataContentBase64(part.image);
      if (data) {
        parts.push({
          inlineData: { mimeType: part.mediaType ?? "image/png", data },
        });
      }
      continue;
    }
    if (part.type === "file") {
      const data = dataContentBase64(part.data);
      if (data) {
        parts.push({
          inlineData: { mimeType: part.mediaType, data },
        });
      }
    }
  }
  return parts;
}

function modelMessageText(message: ModelMessage) {
  const content = message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .map((part) => ("text" in part && typeof part.text === "string" ? part.text : ""))
    .filter(Boolean)
    .join("\n");
}

function dataContentBase64(data: unknown) {
  if (typeof data === "string") {
    const dataUrlMatch = /^data:([^;,]+)?;base64,(.*)$/is.exec(data.trim());
    return dataUrlMatch ? (dataUrlMatch[2] ?? "") : data.trim();
  }
  if (data instanceof ArrayBuffer) return Buffer.from(data).toString("base64");
  if (ArrayBuffer.isView(data)) {
    return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("base64");
  }
  return null;
}

type GoogleGenerateContentResponse = {
  candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  usageMetadata?: {
    promptTokenCount?: number;
    candidatesTokenCount?: number;
    totalTokenCount?: number;
  };
};

function extractGoogleText(response: GoogleGenerateContentResponse) {
  return (
    response.candidates?.[0]?.content?.parts
      ?.map((part) => part.text ?? "")
      .filter(Boolean)
      .join("") ?? ""
  );
}

function googleUsage(response: GoogleGenerateContentResponse) {
  const usage = response.usageMetadata ?? {};
  return {
    inputTokens: usage.promptTokenCount ?? null,
    outputTokens: usage.candidatesTokenCount ?? null,
    totalTokens: usage.totalTokenCount ?? null,
  };
}

function settings(options: RuntimeOptions | null | undefined) {
  const stopSequences = Array.isArray(options?.stopSequences)
    ? options.stopSequences.filter((item) => typeof item === "string" && item.length > 0)
    : undefined;
  return {
    ...defined("temperature", numberOrUndefined(options?.temperature)),
    ...defined("topP", numberOrUndefined(options?.topP)),
    ...defined("topK", numberOrUndefined(options?.topK)),
    ...defined("maxOutputTokens", numberOrUndefined(options?.maxTokens)),
    ...defined("frequencyPenalty", numberOrUndefined(options?.frequencyPenalty)),
    ...defined("presencePenalty", numberOrUndefined(options?.presencePenalty)),
    ...defined("stopSequences", stopSequences?.length ? stopSequences : undefined),
  };
}

function numberOrUndefined(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function requireApiKey(credential: ResolvedCredential, provider: string) {
  if (!credential.apiKey?.trim()) {
    throw upstreamError(`${provider} credential is missing an API key`);
  }
  return credential.apiKey.trim();
}

function defined<K extends string, V>(key: K, value: V | undefined) {
  return value === undefined ? {} : ({ [key]: value } as Record<K, V>);
}
