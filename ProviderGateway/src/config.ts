export const MAX_BODY_BYTES = 32 * 1024 * 1024;

export type GatewayConfig = {
  host: string;
  port: number;
  sharedSecret: string;
  bodyLimitBytes: number;
  bodyReadTimeoutMs: number;
  heartbeatIntervalMs: number;
  maxDeadlineMs: number;
  shutdownGraceMs: number;
  allowPrivateBaseUrls: boolean;
  openRouterBaseUrl: string;
  openRouterAppName: string;
  openRouterReferrer?: string;
  openRouterModelCatalogUrl: string;
  googleBaseUrl: string;
  codexBinary: string;
  codexExecTimeoutMs: number;
  codexOutputLimitBytes: number;
  cliKillGraceMs: number;
  grokBinary: string;
  grokOutputLimitBytes: number;
  grokRpcTimeoutMs: number;
};

export type GatewayConfigOverrides = Partial<GatewayConfig>;

export function loadGatewayConfig(overrides: GatewayConfigOverrides = {}): GatewayConfig {
  const sharedSecret = overrides.sharedSecret ?? process.env.PROVIDER_GATEWAY_SHARED_SECRET ?? "";
  if (!sharedSecret.trim()) {
    throw new Error("PROVIDER_GATEWAY_SHARED_SECRET must be configured");
  }
  return {
    host: overrides.host ?? process.env.PROVIDER_GATEWAY_HOST ?? "127.0.0.1",
    port: overrides.port ?? integerEnv("PROVIDER_GATEWAY_PORT", 8095, 1, 65_535),
    sharedSecret,
    bodyLimitBytes: Math.min(overrides.bodyLimitBytes ?? MAX_BODY_BYTES, MAX_BODY_BYTES),
    bodyReadTimeoutMs:
      overrides.bodyReadTimeoutMs ??
      integerEnv("PROVIDER_GATEWAY_BODY_READ_TIMEOUT_MS", 60_000, 1_000, 300_000),
    heartbeatIntervalMs:
      overrides.heartbeatIntervalMs ??
      integerEnv("PROVIDER_GATEWAY_HEARTBEAT_INTERVAL_MS", 15_000, 1_000, 60_000),
    maxDeadlineMs:
      overrides.maxDeadlineMs ??
      integerEnv("PROVIDER_GATEWAY_MAX_DEADLINE_MS", 20 * 60_000, 1_000, 30 * 60_000),
    shutdownGraceMs:
      overrides.shutdownGraceMs ??
      integerEnv("PROVIDER_GATEWAY_SHUTDOWN_GRACE_MS", 45_000, 1_000, 300_000),
    allowPrivateBaseUrls:
      overrides.allowPrivateBaseUrls ??
      process.env.PROVIDER_GATEWAY_ALLOW_PRIVATE_BASE_URLS === "true",
    openRouterBaseUrl:
      overrides.openRouterBaseUrl ??
      process.env.OPENROUTER_BASE_URL ??
      "https://openrouter.ai/api/v1",
    openRouterAppName:
      overrides.openRouterAppName ?? process.env.OPENROUTER_APP_NAME ?? "chat-sribiddi",
    openRouterReferrer: overrides.openRouterReferrer ?? process.env.OPENROUTER_REFERRER,
    openRouterModelCatalogUrl:
      overrides.openRouterModelCatalogUrl ??
      process.env.OPENROUTER_MODEL_CATALOG_URL ??
      "https://openrouter.ai/api/v1/models",
    googleBaseUrl:
      overrides.googleBaseUrl ??
      process.env.GOOGLE_GENERATIVE_AI_BASE_URL ??
      "https://generativelanguage.googleapis.com/v1beta",
    codexBinary: overrides.codexBinary ?? process.env.CODEX_CLI_BINARY ?? "codex",
    codexExecTimeoutMs:
      overrides.codexExecTimeoutMs ??
      integerEnv("CODEX_EXEC_TIMEOUT_MS", 300_000, 1_000, 30 * 60_000),
    codexOutputLimitBytes:
      overrides.codexOutputLimitBytes ??
      integerEnv("CODEX_EXEC_MAX_OUTPUT_BYTES", 8 * 1024 * 1024, 64 * 1024, 64 * 1024 * 1024),
    cliKillGraceMs:
      overrides.cliKillGraceMs ??
      integerEnv("PROVIDER_GATEWAY_CLI_KILL_GRACE_MS", 5_000, 100, 30_000),
    grokBinary: overrides.grokBinary ?? process.env.GROK_CLI_BIN ?? "grok",
    grokOutputLimitBytes:
      overrides.grokOutputLimitBytes ??
      integerEnv("GROK_CLI_MAX_OUTPUT_BYTES", 1024 * 1024, 64 * 1024, 32 * 1024 * 1024),
    grokRpcTimeoutMs:
      overrides.grokRpcTimeoutMs ??
      integerEnv("GROK_CLI_REQUEST_TIMEOUT_MS", 120_000, 1_000, 300_000),
  };
}

function integerEnv(name: string, fallback: number, min: number, max: number) {
  const raw = process.env[name];
  if (!raw?.trim()) return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}
