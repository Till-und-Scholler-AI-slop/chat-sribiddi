import {
  type GatewayCatalogModel,
  PROVIDER_CONTRACT_VERSION,
  type ProviderModelCatalogResponse,
  ProviderModelCatalogResponseSchema,
} from "@chat-sribiddi/provider-contract";
import type { GatewayConfig } from "./config.js";
import { GatewayError } from "./errors.js";

const FRESH_TTL_MS = 5 * 60_000;
const STALE_TTL_MS = 24 * 60 * 60_000;
const FETCH_TIMEOUT_MS = 15_000;

type CachedCatalog = {
  value: ProviderModelCatalogResponse;
  freshUntil: number;
  staleUntil: number;
};

export class ProviderModelCatalog {
  private cache: CachedCatalog | undefined;
  private inFlight: Promise<ProviderModelCatalogResponse> | undefined;

  constructor(
    private readonly config: GatewayConfig,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async list(provider: string) {
    if (provider.trim().toLowerCase() !== "openrouter") {
      throw new GatewayError(
        "provider_not_supported",
        "Live model catalog is currently available for OpenRouter only",
        400,
        false,
      );
    }
    const now = Date.now();
    if (this.cache && now < this.cache.freshUntil) return this.cache.value;
    if (!this.inFlight) {
      this.inFlight = this.refresh().finally(() => {
        this.inFlight = undefined;
      });
    }
    try {
      return await this.inFlight;
    } catch (error) {
      if (this.cache && now < this.cache.staleUntil) {
        return { ...this.cache.value, stale: true };
      }
      if (error instanceof GatewayError) throw error;
      throw new GatewayError("catalog_unavailable", "Model catalog is unavailable", 503, true);
    }
  }

  private async refresh() {
    let response: Response;
    try {
      response = await this.fetchImpl(this.config.openRouterModelCatalogUrl, {
        headers: {
          Accept: "application/json",
          "HTTP-Referer": this.config.openRouterReferrer ?? "https://germinai.de",
          "X-Title": this.config.openRouterAppName,
        },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
    } catch {
      throw new GatewayError("catalog_unavailable", "Model catalog request failed", 503, true);
    }
    if (!response.ok) {
      await response.body?.cancel().catch(() => undefined);
      throw new GatewayError(
        "catalog_unavailable",
        `Model catalog returned HTTP ${response.status}`,
        503,
        true,
      );
    }
    const body = (await response.json()) as { data?: Array<Record<string, unknown>> };
    const models = (body.data ?? [])
      .slice(0, 10_000)
      .map(openRouterModel)
      .filter((model): model is GatewayCatalogModel => model !== null);
    const fetchedAt = new Date().toISOString();
    const value = ProviderModelCatalogResponseSchema.parse({
      contractVersion: PROVIDER_CONTRACT_VERSION,
      provider: "openrouter",
      fetchedAt,
      stale: false,
      models,
    });
    const now = Date.now();
    this.cache = {
      value,
      freshUntil: now + FRESH_TTL_MS,
      staleUntil: now + STALE_TTL_MS,
    };
    return value;
  }
}

function openRouterModel(value: Record<string, unknown>): GatewayCatalogModel | null {
  const id = text(value.id);
  if (!id) return null;
  const pricing = record(value.pricing);
  const architecture = record(value.architecture);
  const topProvider = record(value.top_provider);
  return {
    id,
    label: text(value.name) ?? id,
    canonicalSlug: id,
    created: nonnegativeInteger(value.created),
    description: text(value.description),
    contextLength: nonnegativeInteger(value.context_length),
    maxCompletionTokens: nonnegativeInteger(topProvider.max_completion_tokens),
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

function record(value: unknown) {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function text(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function nonnegativeInteger(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? Math.floor(value)
    : null;
}

function stringArray(value: unknown) {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    : null;
}
