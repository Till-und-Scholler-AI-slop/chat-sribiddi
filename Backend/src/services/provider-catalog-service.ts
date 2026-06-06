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

export type ProviderConnectionProfile = {
  id: string;
  label: string;
  authMethod: ProviderAuthMethod;
  defaultBaseUrl?: string | undefined;
  baseUrlEditable: boolean;
  requiresApiKey: boolean;
  requiresModelId: boolean;
  oauthEnabled: boolean;
  status: ProviderConnectionStatus;
  docsUrl?: string | undefined;
};

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
  connectionProfiles: ProviderConnectionProfile[];
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
  connectionProfiles: ProviderConnectionProfile[],
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
    authMethods: [...new Set(connectionProfiles.map((profile) => profile.authMethod))],
    connectionProfiles,
  };
}

function profile(input: {
  id: string;
  label: string;
  authMethod: ProviderAuthMethod;
  defaultBaseUrl?: string | undefined;
  baseUrlEditable?: boolean;
  requiresApiKey?: boolean;
  requiresModelId?: boolean;
  oauthEnabled?: boolean;
  status?: ProviderConnectionStatus;
  docsUrl?: string | undefined;
}): ProviderConnectionProfile {
  return {
    id: input.id,
    label: input.label,
    authMethod: input.authMethod,
    defaultBaseUrl: input.defaultBaseUrl,
    baseUrlEditable: input.baseUrlEditable ?? false,
    requiresApiKey: input.requiresApiKey ?? false,
    requiresModelId: input.requiresModelId ?? false,
    oauthEnabled: input.oauthEnabled ?? false,
    status: input.status ?? "NEEDS_CONNECTION",
    docsUrl: input.docsUrl,
  };
}

function apiKeyProfile(docsUrl?: string) {
  return profile({
    id: "api-key",
    label: "API key",
    authMethod: "API_KEY",
    requiresApiKey: true,
    docsUrl,
  });
}

function oauthProfile(
  id: string,
  label: string,
  authMethod: Extract<ProviderAuthMethod, "OAUTH_PKCE" | "OAUTH_AUTH_CODE" | "DEVICE_CODE">,
  docsUrl?: string,
) {
  return profile({
    id,
    label,
    authMethod,
    oauthEnabled: true,
    status: "NEEDS_CONNECTION",
    docsUrl,
  });
}

function openAiCompatibleProfile(
  id: string,
  label: string,
  defaultBaseUrl: string | undefined,
  docsUrl?: string,
) {
  return profile({
    id,
    label,
    authMethod: "OPENAI_COMPATIBLE",
    defaultBaseUrl,
    baseUrlEditable: true,
    requiresApiKey: true,
    requiresModelId: true,
    docsUrl,
  });
}

function localEndpointProfile(id: string, label: string, defaultBaseUrl: string, docsUrl?: string) {
  return profile({
    id,
    label,
    authMethod: "LOCAL_ENDPOINT",
    defaultBaseUrl,
    baseUrlEditable: true,
    requiresModelId: true,
    status: "NEEDS_LOCAL_ENDPOINT",
    docsUrl,
  });
}

function gateway(
  id: string,
  label: string,
  defaultBaseUrl: string | undefined,
  docsUrl?: string,
  category = "llm",
) {
  return descriptor(
    id,
    label,
    category,
    category !== "audio",
    false,
    false,
    "gateway",
    "NEEDS_CONNECTION",
    [openAiCompatibleProfile("api", "API billing", defaultBaseUrl, docsUrl)],
  );
}

function buildCatalog(): ProviderDescriptor[] {
  return [
    descriptor("openai", "OpenAI", "llm", true, false, false, "native", "NEEDS_CONNECTION", [
      apiKeyProfile("https://platform.openai.com/docs/api-reference/authentication"),
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
      [
        apiKeyProfile("https://platform.claude.com/docs/en/api/authentication/overview"),
        profile({
          id: "claude-cli-session",
          label: "Claude subscription / CLI session",
          authMethod: "CLI_SESSION",
          status: "EXPERIMENTAL",
          docsUrl: "https://code.claude.com/docs/en/authentication",
        }),
      ],
    ),
    descriptor("google", "Google Gemini", "llm", true, false, false, "native", "NEEDS_CONNECTION", [
      apiKeyProfile("https://ai.google.dev/gemini-api/docs/api-key"),
      oauthProfile(
        "google-oauth",
        "Google OAuth",
        "OAUTH_AUTH_CODE",
        "https://cloud.google.com/docs/authentication",
      ),
      profile({
        id: "service-account",
        label: "Service account",
        authMethod: "SERVICE_ACCOUNT",
        status: "EXPERIMENTAL",
        docsUrl: "https://cloud.google.com/iam/docs/service-account-overview",
      }),
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
      [
        apiKeyProfile("https://openrouter.ai/docs/api-keys"),
        oauthProfile(
          "openrouter-oauth",
          "OpenRouter OAuth",
          "OAUTH_PKCE",
          "https://openrouter.ai/docs/api-keys",
        ),
      ],
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
      [apiKeyProfile("https://api-dashboard.search.brave.com/app/keys")],
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
      [
        oauthProfile(
          "github-oauth",
          "GitHub OAuth device flow",
          "DEVICE_CODE",
          "https://docs.github.com/en/copilot/how-tos/copilot-sdk/set-up-copilot-sdk/github-oauth",
        ),
        profile({
          id: "copilot-cli-session",
          label: "Copilot CLI session",
          authMethod: "SUBSCRIPTION_CLI",
          status: "EXPERIMENTAL",
          docsUrl:
            "https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli",
        }),
      ],
    ),
    descriptor("qwen", "Qwen Cloud", "llm", true, false, false, "gateway", "NEEDS_CONNECTION", [
      openAiCompatibleProfile(
        "api",
        "API billing",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
      ),
      openAiCompatibleProfile(
        "coding-plan",
        "Coding Plan",
        "https://coding-intl.dashscope.aliyuncs.com/v1",
        "https://docs.qwencloud.com/coding-plan/overview",
      ),
      profile({
        id: "portal",
        label: "Qwen portal session",
        authMethod: "CLI_SESSION",
        status: "EXPERIMENTAL",
        docsUrl: "https://docs.qwencloud.com/coding-plan/overview",
      }),
    ]),
    descriptor("minimax", "MiniMax", "llm", true, false, false, "gateway", "NEEDS_CONNECTION", [
      openAiCompatibleProfile(
        "api",
        "API billing",
        "https://api.minimax.io/v1",
        "https://platform.minimax.io/docs/api-reference/text-chat",
      ),
      openAiCompatibleProfile(
        "token-plan",
        "Token Plan",
        "https://api.minimax.io/v1",
        "https://platform.minimax.io/docs/token-plan/intro",
      ),
      profile({
        id: "portal",
        label: "MiniMax portal session",
        authMethod: "CLI_SESSION",
        status: "EXPERIMENTAL",
        docsUrl: "https://platform.minimax.io/docs/token-plan/intro",
      }),
    ]),
    descriptor("zai", "Z.AI", "llm", true, false, false, "gateway", "NEEDS_CONNECTION", [
      openAiCompatibleProfile(
        "api",
        "API billing",
        "https://api.z.ai/api/paas/v4",
        "https://docs.z.ai/api-reference/introduction",
      ),
      openAiCompatibleProfile(
        "coding-plan",
        "Coding Plan",
        "https://api.z.ai/api/coding/paas/v4",
        "https://docs.z.ai/api-reference/introduction",
      ),
    ]),
    gateway("openai-compatible", "OpenAI Compatible", undefined),
    descriptor("ollama", "Ollama", "local", true, true, false, "gateway", "NEEDS_LOCAL_ENDPOINT", [
      localEndpointProfile(
        "local",
        "Local endpoint",
        "http://localhost:11434/v1",
        "https://docs.ollama.com/api/openai-compatibility",
      ),
    ]),
    descriptor(
      "lm-studio",
      "LM Studio",
      "local",
      true,
      true,
      false,
      "gateway",
      "NEEDS_LOCAL_ENDPOINT",
      [
        localEndpointProfile(
          "local",
          "Local endpoint",
          "http://localhost:1234/v1",
          "https://lmstudio.ai/docs/developer/openai-compat/",
        ),
      ],
    ),
    descriptor(
      "llama-cpp",
      "llama.cpp",
      "local",
      true,
      true,
      false,
      "gateway",
      "NEEDS_LOCAL_ENDPOINT",
      [localEndpointProfile("local", "Local endpoint", "http://localhost:8080/v1")],
    ),
    descriptor("vllm", "vLLM", "local", true, true, false, "gateway", "NEEDS_LOCAL_ENDPOINT", [
      localEndpointProfile(
        "local",
        "Local endpoint",
        "http://localhost:8000/v1",
        "https://docs.vllm.ai/en/v0.19.0/serving/openai_compatible_server/",
      ),
    ]),
    gateway("mistral", "Mistral AI", "https://api.mistral.ai/v1", "https://docs.mistral.ai/api/"),
    gateway("deepseek", "DeepSeek", "https://api.deepseek.com", "https://api-docs.deepseek.com/"),
    gateway(
      "groq",
      "Groq",
      "https://api.groq.com/openai/v1",
      "https://console.groq.com/docs/overview",
    ),
    descriptor("xai", "xAI", "llm", true, false, false, "gateway", "NEEDS_CONNECTION", [
      openAiCompatibleProfile(
        "api",
        "API billing",
        "https://api.x.ai/v1",
        "https://docs.x.ai/developers/rest-api-reference/inference",
      ),
      profile({
        id: "grok-build-subscription",
        label: "Grok Build subscription",
        authMethod: "SUBSCRIPTION_CLI",
        oauthEnabled: true,
        status: "EXPERIMENTAL",
        docsUrl: "https://docs.x.ai/build/enterprise",
      }),
    ]),
    gateway(
      "perplexity",
      "Perplexity AI",
      "https://api.perplexity.ai",
      "https://docs.perplexity.ai/docs/sonar/openai-compatibility",
    ),
    gateway(
      "together-ai",
      "Together AI",
      "https://api.together.ai/v1",
      "https://docs.together.ai/docs/inference/openai-compatibility",
    ),
    descriptor(
      "mock",
      "Mock / Testing",
      "testing",
      true,
      false,
      false,
      "gateway",
      "NEEDS_CONNECTION",
      [openAiCompatibleProfile("api", "API billing", "http://localhost:9999/v1")],
    ),
  ];
}
