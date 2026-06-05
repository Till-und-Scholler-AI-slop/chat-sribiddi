export const hasText = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;

export const normalizeTitle = (value: string | null | undefined) => {
  const title = value?.trim();
  return title && title.length > 0 ? title.slice(0, 255) : "New chat";
};

export const canonicalProviderId = (providerId: string | null | undefined) => {
  const normalized = (providerId ?? "").trim().replaceAll("_", "-").toLowerCase();
  const aliases: Record<string, string> = {
    claude: "anthropic",
    anthropic: "anthropic",
    gemini: "google",
    brave: "brave-search",
    "brave-search": "brave-search",
    brave_search: "brave-search",
    openai: "openai",
    google: "google",
    openrouter: "openrouter",
    copilot: "github-copilot",
    "github-copilot": "github-copilot",
    "z-ai": "zai",
    "z.ai": "zai",
  };
  return aliases[normalized] ?? normalized;
};

export const providerIdToLegacy = (providerId: string) => {
  switch (canonicalProviderId(providerId)) {
    case "openai":
      return "OPENAI" as const;
    case "google":
      return "GOOGLE" as const;
    case "anthropic":
      return "CLAUDE" as const;
    case "openrouter":
      return "OPENROUTER" as const;
    case "brave-search":
      return "BRAVE_SEARCH" as const;
    default:
      return null;
  }
};

export const legacyToProviderId = (provider: string) => {
  switch (provider) {
    case "OPENAI":
      return "openai";
    case "GOOGLE":
      return "google";
    case "CLAUDE":
      return "anthropic";
    case "OPENROUTER":
      return "openrouter";
    case "BRAVE_SEARCH":
      return "brave-search";
    default:
      return canonicalProviderId(provider);
  }
};
