import { z } from "zod";

export const PROVIDER_CONTRACT_VERSION = "1";
export const PROVIDER_STREAM_PATH = "/v1/provider/stream";
export const PROVIDER_MODEL_CATALOG_PATH = "/v1/provider/models";

const nonBlank = (max) => z.string().trim().min(1).max(max);
const preservedNonBlank = (max) =>
  z
    .string()
    .min(1)
    .max(max)
    .refine((value) => value.trim().length > 0, "String must contain a non-whitespace character");
const secret = nonBlank(16_384);
const providerId = nonBlank(80).regex(/^[A-Za-z0-9][A-Za-z0-9._:-]*$/);
const stringMap = z.record(nonBlank(256), nonBlank(16_384));
const mediaType = nonBlank(255).regex(/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/);
const binaryData = z
  .string()
  .min(1)
  .max(32 * 1024 * 1024)
  .refine(isBase64Data, "Binary data must be raw base64 or a base64 data URL");

export const GatewayJsonValueSchema = z.json();

const textPart = z
  .object({
    type: z.literal("text"),
    text: preservedNonBlank(4 * 1024 * 1024),
  })
  .strict();

const imagePart = z
  .object({
    type: z.literal("image"),
    data: binaryData,
    mediaType: mediaType.refine((value) => value.toLowerCase().startsWith("image/"), "Expected an image media type"),
    filename: nonBlank(512).optional(),
  })
  .strict();

const filePart = z
  .object({
    type: z.literal("file"),
    data: binaryData,
    mediaType,
    filename: nonBlank(512).optional(),
  })
  .strict();

const toolCallPart = z
  .object({
    type: z.literal("tool-call"),
    toolCallId: nonBlank(256),
    toolName: nonBlank(128),
    input: GatewayJsonValueSchema,
  })
  .strict();

const toolResultPart = z
  .object({
    type: z.literal("tool-result"),
    toolCallId: nonBlank(256),
    toolName: nonBlank(128),
    output: GatewayJsonValueSchema,
  })
  .strict();

export const GatewayContentPartSchema = z.discriminatedUnion("type", [
  textPart,
  imagePart,
  filePart,
  toolCallPart,
  toolResultPart,
]);

export const GatewayMessageSchema = z.discriminatedUnion("role", [
  z.object({ role: z.literal("system"), content: preservedNonBlank(4 * 1024 * 1024) }).strict(),
  z
    .object({
      role: z.literal("user"),
      content: z.union([
        preservedNonBlank(4 * 1024 * 1024),
        z.array(z.discriminatedUnion("type", [textPart, imagePart, filePart])).min(1).max(128),
      ]),
    })
    .strict(),
  z
    .object({
      role: z.literal("assistant"),
      content: z.union([
        preservedNonBlank(4 * 1024 * 1024),
        z.array(z.discriminatedUnion("type", [textPart, toolCallPart])).min(1).max(128),
      ]),
    })
    .strict(),
  z.object({ role: z.literal("tool"), content: z.array(toolResultPart).min(1).max(64) }).strict(),
]);

export const GatewayToolSchema = z
  .object({
    name: nonBlank(128).regex(/^[A-Za-z_][A-Za-z0-9_.:-]*$/),
    description: z.string().trim().max(4_096).optional(),
    inputSchema: z.record(z.string(), z.unknown()),
  })
  .strict();

const openAiProvider = z.object({ kind: z.literal("openai"), apiKey: secret }).strict();
const anthropicProvider = z.object({ kind: z.literal("anthropic"), apiKey: secret }).strict();
const googleProvider = z
  .object({
    kind: z.literal("google"),
    credential: z.discriminatedUnion("type", [
      z.object({ type: z.literal("api-key"), apiKey: secret }).strict(),
      z
        .object({
          type: z.literal("oauth"),
          accessToken: secret,
          quotaProjectId: nonBlank(512).optional(),
        })
        .strict(),
    ]),
  })
  .strict();
const openRouterProvider = z
  .object({
    kind: z.literal("openrouter"),
    apiKey: secret,
    appName: nonBlank(200).optional(),
    referrer: z.string().url().max(2_048).optional(),
  })
  .strict();
const openAiCompatibleProvider = z
  .object({
    kind: z.literal("openai-compatible"),
    providerId,
    baseUrl: z.string().url().max(2_048),
    apiKey: z.string().trim().max(16_384).optional(),
    headers: stringMap.optional(),
    queryParams: stringMap.optional(),
  })
  .strict();
const codexProvider = z
  .object({
    kind: z.literal("codex"),
    codexHome: nonBlank(4_096),
  })
  .strict();
const grokCliProvider = z
  .object({
    kind: z.literal("grok-cli"),
    cliHomeDir: nonBlank(4_096),
  })
  .strict();

export const GatewayProviderSchema = z.discriminatedUnion("kind", [
  openAiProvider,
  anthropicProvider,
  googleProvider,
  openRouterProvider,
  openAiCompatibleProvider,
  codexProvider,
  grokCliProvider,
]);

export const ProviderStreamRequestSchema = z
  .object({
    contractVersion: z.literal(PROVIDER_CONTRACT_VERSION),
    requestId: nonBlank(128),
    provider: GatewayProviderSchema,
    modelId: nonBlank(256),
    messages: z.array(GatewayMessageSchema).min(1).max(200),
    tools: z.array(GatewayToolSchema).max(64).optional(),
    toolChoice: z.enum(["auto", "none", "required"]).optional(),
    options: z
      .object({
        temperature: z.number().finite().min(0).max(2).optional(),
        topP: z.number().finite().min(0).max(1).optional(),
        topK: z.number().int().positive().max(1_000).optional(),
        maxOutputTokens: z.number().int().positive().max(1_000_000).optional(),
        frequencyPenalty: z.number().finite().min(-2).max(2).optional(),
        presencePenalty: z.number().finite().min(-2).max(2).optional(),
        stopSequences: z.array(nonBlank(1_024)).max(16).optional(),
      })
      .strict()
      .optional(),
    timeoutMs: z.number().int().min(1_000).max(30 * 60_000).optional(),
  })
  .strict();

const nullableOptionalText = z.string().max(16_384).nullable().optional();
const nullableOptionalInteger = z.number().int().nonnegative().nullable().optional();

export const GatewayCatalogModelSchema = z
  .object({
    id: nonBlank(512),
    label: nonBlank(512),
    canonicalSlug: nullableOptionalText,
    created: nullableOptionalInteger,
    description: nullableOptionalText,
    contextLength: nullableOptionalInteger,
    maxCompletionTokens: nullableOptionalInteger,
    promptPrice: nullableOptionalText,
    completionPrice: nullableOptionalText,
    requestPrice: nullableOptionalText,
    imagePrice: nullableOptionalText,
    modality: nullableOptionalText,
    inputModalities: z.array(nonBlank(128)).max(32).nullable().optional(),
    outputModalities: z.array(nonBlank(128)).max(32).nullable().optional(),
    supportedParameters: z.array(nonBlank(128)).max(256).nullable().optional(),
  })
  .strict();

export const ProviderModelCatalogResponseSchema = z
  .object({
    contractVersion: z.literal(PROVIDER_CONTRACT_VERSION),
    provider: providerId,
    fetchedAt: z.string().datetime({ offset: true }),
    stale: z.boolean(),
    models: z.array(GatewayCatalogModelSchema).max(10_000),
  })
  .strict();

const eventBase = {
  contractVersion: z.literal(PROVIDER_CONTRACT_VERSION),
  requestId: nonBlank(128),
  sequence: z.number().int().nonnegative(),
  timestamp: z.string().datetime({ offset: true }),
};

export const ProviderStreamEventSchema = z.discriminatedUnion("type", [
  z
    .object({
      ...eventBase,
      type: z.literal("provider_started"),
      provider: z.enum(["openai", "anthropic", "google", "openrouter", "openai-compatible", "codex", "grok-cli"]),
      modelId: nonBlank(256),
    })
    .strict(),
  z.object({ ...eventBase, type: z.literal("text_delta"), delta: z.string().min(1) }).strict(),
  z.object({ ...eventBase, type: z.literal("reasoning_delta"), delta: z.string().min(1) }).strict(),
  z
    .object({
      ...eventBase,
      type: z.literal("tool_call"),
      toolCallId: nonBlank(256),
      toolName: nonBlank(128),
      input: z.unknown(),
    })
    .strict(),
  z
    .object({
      ...eventBase,
      type: z.literal("usage"),
      inputTokens: z.number().int().nonnegative().nullable().optional(),
      outputTokens: z.number().int().nonnegative().nullable().optional(),
      totalTokens: z.number().int().nonnegative().nullable().optional(),
      reasoningTokens: z.number().int().nonnegative().nullable().optional(),
      cachedInputTokens: z.number().int().nonnegative().nullable().optional(),
    })
    .strict(),
  z
    .object({
      ...eventBase,
      type: z.literal("done"),
      finishReason: z.string().trim().max(128).optional(),
    })
    .strict(),
  z
    .object({
      ...eventBase,
      type: z.literal("error"),
      code: nonBlank(128),
      message: nonBlank(4_096),
      retryable: z.boolean(),
    })
    .strict(),
]);

export function parseProviderStreamRequest(value) {
  return ProviderStreamRequestSchema.parse(value);
}

export function parseProviderStreamEvent(value) {
  return ProviderStreamEventSchema.parse(value);
}

export function formatProviderSseEvent(value) {
  const event = parseProviderStreamEvent(value);
  return `event: ${event.type}\nid: ${event.sequence}\ndata: ${JSON.stringify(event)}\n\n`;
}

function isBase64Data(value) {
  const match = /^data:[^,;]+(?:;[^,;]+)*;base64,(.+)$/i.exec(value);
  const payload = match?.[1] ?? (value.startsWith("data:") ? "" : value);
  if (!payload || !/^[A-Za-z0-9+/]+={0,2}$/.test(payload)) return false;
  const unpaddedLength = payload.replace(/=+$/, "").length;
  return unpaddedLength % 4 !== 1;
}
