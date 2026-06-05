import { canonicalProviderId, providerIdToLegacy } from "../utils/strings.js";

export type ProviderAuthMethod =
  | "API_KEY"
  | "OAUTH_PKCE"
  | "OAUTH_AUTH_CODE"
  | "DEVICE_CODE"
  | "SERVICE_ACCOUNT"
  | "LOCAL_ENDPOINT"
  | "OPENAI_COMPATIBLE"
  | "SUBSCRIPTION_CLI"
  | "CLI_SESSION"
  | "CUSTOM_HTTP";

export type ProviderConnectionStatus =
  | "READY"
  | "NEEDS_CONNECTION"
  | "NEEDS_LOCAL_ENDPOINT"
  | "EXPERIMENTAL"
  | "DISABLED"
  | "ERROR";

export type ProviderDescriptor = {
  id: string;
  label: string;
  category: string;
  chatProvider: boolean;
  localOnly: boolean;
  experimental: boolean;
  execution: "native" | "gateway" | "tool";
  defaultStatus: ProviderConnectionStatus;
  authMethods: ProviderAuthMethod[];
};

export class ProviderCatalogService {
  private readonly providers = new Map<string, ProviderDescriptor>(
    buildCatalog().map((provider) => [provider.id, provider]),
  );

  listProviders() {
    return [...this.providers.values()].sort((a, b) =>
      a.label.localeCompare(b.label, undefined, { sensitivity: "base" }),
    );
  }

  find(providerId: string | null | undefined) {
    return this.providers.get(canonicalProviderId(providerId));
  }

  canonicalProviderId(providerId: string | null | undefined) {
    return canonicalProviderId(providerId);
  }

  legacyProvider(providerId: string | null | undefined) {
    return providerIdToLegacy(canonicalProviderId(providerId));
  }
}

function descriptor(
  id: string,
  label: string,
  category: string,
  chatProvider: boolean,
  localOnly: boolean,
  experimental: boolean,
  execution: ProviderDescriptor["execution"],
  defaultStatus: ProviderConnectionStatus,
  authMethods: ProviderAuthMethod[],
): ProviderDescriptor {
  return {
    id,
    label,
    category,
    chatProvider,
    localOnly,
    experimental,
    execution,
    defaultStatus,
    authMethods,
  };
}

function gateway(id: string, label: string, category = "llm", localOnly = false) {
  return descriptor(
    id,
    label,
    category,
    category !== "audio",
    localOnly,
    false,
    "gateway",
    localOnly ? "NEEDS_LOCAL_ENDPOINT" : "NEEDS_CONNECTION",
    localOnly ? ["LOCAL_ENDPOINT"] : ["API_KEY", "OPENAI_COMPATIBLE"],
  );
}

function buildCatalog(): ProviderDescriptor[] {
  return [
    descriptor("openai", "OpenAI", "llm", true, false, false, "native", "NEEDS_CONNECTION", [
      "API_KEY",
      "OAUTH_AUTH_CODE",
    ]),
    descriptor(
      "anthropic",
      "Anthropic Claude",
      "llm",
      true,
      false,
      false,
      "native",
      "NEEDS_CONNECTION",
      ["API_KEY", "CLI_SESSION"],
    ),
    descriptor("google", "Google Gemini", "llm", true, false, false, "native", "NEEDS_CONNECTION", [
      "API_KEY",
      "OAUTH_AUTH_CODE",
      "SERVICE_ACCOUNT",
    ]),
    descriptor(
      "openrouter",
      "OpenRouter",
      "llm",
      true,
      false,
      false,
      "native",
      "NEEDS_CONNECTION",
      ["API_KEY", "OAUTH_PKCE"],
    ),
    descriptor(
      "brave-search",
      "Brave Search",
      "tool",
      false,
      false,
      false,
      "tool",
      "NEEDS_CONNECTION",
      ["API_KEY"],
    ),
    descriptor(
      "github-copilot",
      "GitHub Copilot",
      "llm",
      true,
      false,
      true,
      "gateway",
      "EXPERIMENTAL",
      ["DEVICE_CODE", "SUBSCRIPTION_CLI"],
    ),
    gateway("qwen", "Qwen Cloud"),
    descriptor(
      "qwen-oauth",
      "Qwen OAuth / Portal",
      "llm",
      true,
      false,
      true,
      "gateway",
      "EXPERIMENTAL",
      ["OAUTH_AUTH_CODE"],
    ),
    gateway("minimax", "MiniMax"),
    descriptor(
      "minimax-portal",
      "MiniMax Portal",
      "llm",
      true,
      false,
      true,
      "gateway",
      "EXPERIMENTAL",
      ["OAUTH_AUTH_CODE"],
    ),
    gateway("zai", "Z.AI"),
    gateway("openai-compatible", "OpenAI Compatible"),
    gateway("ollama", "Ollama", "local", true),
    gateway("lm-studio", "LM Studio", "local", true),
    gateway("llama-cpp", "llama.cpp", "local", true),
    gateway("vllm", "vLLM", "local", true),
    gateway("mistral", "Mistral AI"),
    gateway("deepseek", "DeepSeek"),
    gateway("groq", "Groq"),
    gateway("xai", "xAI"),
    gateway("perplexity", "Perplexity AI"),
    gateway("together-ai", "Together AI"),
    gateway("mock", "Mock / Testing", "testing"),
  ];
}
