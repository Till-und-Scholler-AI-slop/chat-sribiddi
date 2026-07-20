import type { z } from "zod";

export const PROVIDER_CONTRACT_VERSION: "1";
export const PROVIDER_STREAM_PATH: "/v1/provider/stream";
export const PROVIDER_MODEL_CATALOG_PATH: "/v1/provider/models";

export type GatewayJsonValue =
  | null
  | boolean
  | number
  | string
  | GatewayJsonValue[]
  | { [key: string]: GatewayJsonValue };

export type GatewayContentPart =
  | { type: "text"; text: string }
  | { type: "image"; data: string; mediaType: string; filename?: string }
  | { type: "file"; data: string; mediaType: string; filename?: string }
  | { type: "tool-call"; toolCallId: string; toolName: string; input: GatewayJsonValue }
  | { type: "tool-result"; toolCallId: string; toolName: string; output: GatewayJsonValue };

export type GatewayMessage =
  | { role: "system"; content: string }
  | {
      role: "user";
      content: string | Array<Extract<GatewayContentPart, { type: "text" | "image" | "file" }>>;
    }
  | {
      role: "assistant";
      content: string | Array<Extract<GatewayContentPart, { type: "text" | "tool-call" }>>;
    }
  | { role: "tool"; content: Array<Extract<GatewayContentPart, { type: "tool-result" }>> };

export type GatewayTool = {
  name: string;
  description?: string;
  inputSchema: Record<string, unknown>;
};

export type GatewayProvider =
  | { kind: "openai"; apiKey: string }
  | { kind: "anthropic"; apiKey: string }
  | {
      kind: "google";
      credential:
        | { type: "api-key"; apiKey: string }
        | { type: "oauth"; accessToken: string; quotaProjectId?: string };
    }
  | { kind: "openrouter"; apiKey: string; appName?: string; referrer?: string }
  | {
      kind: "openai-compatible";
      providerId: string;
      baseUrl: string;
      apiKey?: string;
      headers?: Record<string, string>;
      queryParams?: Record<string, string>;
    }
  | { kind: "codex"; codexHome: string }
  | { kind: "grok-cli"; cliHomeDir: string };

export type ProviderStreamRequest = {
  contractVersion: "1";
  requestId: string;
  provider: GatewayProvider;
  modelId: string;
  messages: GatewayMessage[];
  tools?: GatewayTool[];
  toolChoice?: "auto" | "none" | "required";
  options?: {
    temperature?: number;
    topP?: number;
    topK?: number;
    maxOutputTokens?: number;
    frequencyPenalty?: number;
    presencePenalty?: number;
    stopSequences?: string[];
  };
  timeoutMs?: number;
};

export type GatewayCatalogModel = {
  id: string;
  label: string;
  canonicalSlug?: string | null;
  created?: number | null;
  description?: string | null;
  contextLength?: number | null;
  maxCompletionTokens?: number | null;
  promptPrice?: string | null;
  completionPrice?: string | null;
  requestPrice?: string | null;
  imagePrice?: string | null;
  modality?: string | null;
  inputModalities?: string[] | null;
  outputModalities?: string[] | null;
  supportedParameters?: string[] | null;
};

export type ProviderModelCatalogResponse = {
  contractVersion: "1";
  provider: string;
  fetchedAt: string;
  stale: boolean;
  models: GatewayCatalogModel[];
};

type EventBase = {
  contractVersion: "1";
  requestId: string;
  sequence: number;
  timestamp: string;
};

export type ProviderStreamEvent =
  | (EventBase & {
      type: "provider_started";
      provider: GatewayProvider["kind"];
      modelId: string;
    })
  | (EventBase & { type: "text_delta"; delta: string })
  | (EventBase & { type: "reasoning_delta"; delta: string })
  | (EventBase & { type: "tool_call"; toolCallId: string; toolName: string; input: unknown })
  | (EventBase & {
      type: "usage";
      inputTokens?: number | null;
      outputTokens?: number | null;
      totalTokens?: number | null;
      reasoningTokens?: number | null;
      cachedInputTokens?: number | null;
    })
  | (EventBase & { type: "done"; finishReason?: string })
  | (EventBase & { type: "error"; code: string; message: string; retryable: boolean });

export const GatewayMessageSchema: z.ZodType<GatewayMessage>;
export const GatewayContentPartSchema: z.ZodType<GatewayContentPart>;
export const GatewayJsonValueSchema: z.ZodType<GatewayJsonValue>;
export const GatewayToolSchema: z.ZodType<GatewayTool>;
export const GatewayProviderSchema: z.ZodType<GatewayProvider>;
export const ProviderStreamRequestSchema: z.ZodType<ProviderStreamRequest>;
export const ProviderStreamEventSchema: z.ZodType<ProviderStreamEvent>;
export const GatewayCatalogModelSchema: z.ZodType<GatewayCatalogModel>;
export const ProviderModelCatalogResponseSchema: z.ZodType<ProviderModelCatalogResponse>;

export function parseProviderStreamRequest(value: unknown): ProviderStreamRequest;
export function parseProviderStreamEvent(value: unknown): ProviderStreamEvent;
export function formatProviderSseEvent(value: ProviderStreamEvent): string;
