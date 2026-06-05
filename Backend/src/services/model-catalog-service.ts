import { readFileSync } from "node:fs";
import { join } from "node:path";
import YAML from "yaml";
import { env } from "../config/env.js";
import type { Provider } from "../db/types.js";

export type AvailabilityFlag = "READY" | "NEEDS_CONNECTION" | "ALWAYS";

export type ModelOption = {
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
  availability?: AvailabilityFlag | null;
};

type CatalogConfig = Record<string, ModelOption[]>;

export class ModelCatalogService {
  private readonly fallback: CatalogConfig;
  private readonly featured: Record<string, string[]>;

  constructor(configPath = join(process.cwd(), "src/config/model-catalog.yaml")) {
    const config = YAML.parse(readFileSync(configPath, "utf8")) as {
      models?: Record<string, unknown>;
      ai?: { models?: Record<string, unknown> };
    };
    const models = config.models ?? config.ai?.models ?? {};
    this.featured = (models.featured as Record<string, string[]> | undefined) ?? {};
    this.fallback = {
      openai: normalizeConfigModels(models.openai),
      google: normalizeConfigModels(models.google),
      claude: normalizeConfigModels(models.claude),
      openrouter: normalizeConfigModels(models.openrouter),
    };
  }

  listFallback(provider: Provider) {
    return this.fallback[configKey(provider)] ?? [];
  }

  async listModels(provider: Provider, apiKey: string | null) {
    if (provider === "OPENROUTER") {
      const live = await this.fetchOpenRouterModels(apiKey);
      return live.length > 0 ? live : this.listFallback(provider);
    }
    return this.listFallback(provider);
  }

  compactModels(provider: Provider, models: ModelOption[]) {
    const featured = new Set(this.featured[configKey(provider)] ?? []);
    const always = provider === "OPENROUTER" ? new Set(["openrouter/free"]) : new Set<string>();
    const selected = models.filter((model) => featured.has(model.id) || always.has(model.id));
    return selected.length > 0 ? selected : models.slice(0, 12);
  }

  annotateModels(
    provider: Provider,
    models: ModelOption[],
    hasUserKey: boolean,
    userId?: string | null,
  ) {
    return models.map((model) => {
      const availability: AvailabilityFlag =
        hasUserKey || (provider === "OPENROUTER" && model.id === "openrouter/free" && !userId)
          ? "READY"
          : "NEEDS_CONNECTION";
      return { ...model, availability };
    });
  }

  private async fetchOpenRouterModels(apiKey: string | null) {
    if (!apiKey) return [];
    try {
      const response = await fetch(env.OPENROUTER_MODEL_CATALOG_URL, {
        headers: { Authorization: `Bearer ${apiKey}` },
        signal: AbortSignal.timeout(15_000),
      });
      if (!response.ok) return [];
      const json = (await response.json()) as { data?: Array<Record<string, unknown>> };
      return (json.data ?? [])
        .map(openRouterModel)
        .filter((item): item is ModelOption => item !== null);
    } catch {
      return [];
    }
  }
}

function configKey(provider: Provider) {
  return provider === "CLAUDE" ? "claude" : provider.toLowerCase();
}

function normalizeConfigModels(value: unknown): ModelOption[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item): ModelOption | null => {
      if (!item || typeof item !== "object") return null;
      const source = item as Record<string, unknown>;
      const id = text(source.id);
      if (!id) return null;
      return {
        id,
        label: text(source.label) ?? id,
        promptPrice: text(source["prompt-price"]) ?? text(source.promptPrice),
        completionPrice: text(source["completion-price"]) ?? text(source.completionPrice),
        requestPrice: text(source["request-price"]) ?? text(source.requestPrice),
        imagePrice: text(source["image-price"]) ?? text(source.imagePrice),
      } satisfies ModelOption;
    })
    .filter((item): item is ModelOption => item !== null);
}

function openRouterModel(value: Record<string, unknown>): ModelOption | null {
  const id = text(value.id);
  if (!id) return null;
  const pricing =
    typeof value.pricing === "object" && value.pricing
      ? (value.pricing as Record<string, unknown>)
      : {};
  const architecture =
    typeof value.architecture === "object" && value.architecture
      ? (value.architecture as Record<string, unknown>)
      : {};
  const topProvider =
    typeof value.top_provider === "object" && value.top_provider
      ? (value.top_provider as Record<string, unknown>)
      : {};
  return {
    id,
    label: text(value.name) ?? id,
    canonicalSlug: id,
    created: numberOrNull(value.created),
    description: text(value.description),
    contextLength: numberOrNull(value.context_length),
    maxCompletionTokens: numberOrNull(topProvider.max_completion_tokens),
    promptPrice: text(pricing.prompt),
    completionPrice: text(pricing.completion),
    requestPrice: text(pricing.request),
    imagePrice: text(pricing.image),
    modality: text(architecture.modality),
    inputModalities: stringArray(architecture.input_modalities),
    outputModalities: stringArray(architecture.output_modalities),
    supportedParameters: stringArray(value.supported_parameters),
  };
}

function text(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function numberOrNull(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function stringArray(value: unknown) {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string")
    : null;
}
