import { createAnthropic } from "@ai-sdk/anthropic";
import { createGoogleGenerativeAI } from "@ai-sdk/google";
import { createOpenAI } from "@ai-sdk/openai";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { generateText, type ModelMessage, stepCountIs, streamText, type ToolSet } from "ai";
import { env } from "../config/env.js";
import type { Provider } from "../db/types.js";
import { upstreamError } from "../errors/http-error.js";
import type { ResolvedCredential } from "./credential-access-service.js";
import type { GrokCliBridge, GrokCliSession } from "./grok-cli-bridge-service.js";

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
};

export class AiChatService {
  constructor(private readonly grokCli?: GrokCliBridge | undefined) {}

  stream(input: ChatExecutionRequest) {
    if (isGrokCliSubscription(input)) {
      return streamGrokCliSubscription(input, this.grokCli);
    }
    if (isGoogleOAuth(input)) {
      return streamGoogleOAuth(input);
    }
    return streamText({
      model: languageModel(input),
      messages: input.messages,
      ...toolSettings(input.tools),
      ...settings(input.options),
    });
  }

  async generate(input: ChatExecutionRequest) {
    if (isGrokCliSubscription(input)) {
      return generateGrokCliSubscription(input, this.grokCli);
    }
    if (isGoogleOAuth(input)) {
      return generateGoogleOAuth(input);
    }
    return generateText({
      model: languageModel(input),
      messages: input.messages,
      ...toolSettings(input.tools),
      ...settings(input.options),
    });
  }
}

function isGrokCliSubscription(input: ChatExecutionRequest) {
  return (
    input.provider === "xai" &&
    input.credential.connection?.authMethod === "SUBSCRIPTION_CLI"
  );
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

function streamGrokCliSubscription(
  input: ChatExecutionRequest,
  bridge: GrokCliBridge | undefined,
) {
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
  const response = await fetch(
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
