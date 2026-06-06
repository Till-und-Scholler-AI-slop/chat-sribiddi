import { randomBytes } from "node:crypto";
import { existsSync } from "node:fs";
import { join } from "node:path";
import dotenv from "dotenv";
import { z } from "zod";

if (existsSync(join(process.cwd(), ".env"))) {
  dotenv.config();
}

const durationMs = (value: string | undefined, fallback: number) => {
  if (!value?.trim()) return fallback;
  const raw = value.trim();
  const match = raw.match(/^(\d+)(ms|s|m|h|d)?$/i);
  if (!match) return fallback;
  const amount = Number(match[1]);
  const unit = (match[2] || "ms").toLowerCase();
  const multiplier =
    unit === "d"
      ? 86_400_000
      : unit === "h"
        ? 3_600_000
        : unit === "m"
          ? 60_000
          : unit === "s"
            ? 1_000
            : 1;
  return amount * multiplier;
};

const jdbcToPgUrl = (value: string | undefined) => {
  if (!value?.startsWith("jdbc:postgresql://")) return value;
  return `postgresql://${value.slice("jdbc:postgresql://".length)}`;
};

const randomDevSecret = () => randomBytes(32).toString("base64");

const envSchema = z.object({
  NODE_ENV: z.string().default("development"),
  PORT: z.coerce.number().int().positive().default(8080),
  HOST: z.string().default("0.0.0.0"),
  DATABASE_URL: z.string().optional(),
  SPRING_DATASOURCE_URL: z.string().optional(),
  SPRING_DATASOURCE_USERNAME: z.string().optional(),
  SPRING_DATASOURCE_PASSWORD: z.string().optional(),
  POSTGRES_DB: z.string().default("chat_sribiddi"),
  POSTGRES_HOST: z.string().default("localhost"),
  POSTGRES_PORT: z.coerce.number().int().positive().default(5432),
  POSTGRES_USER: z.string().optional(),
  POSTGRES_PASSWORD: z.string().optional(),
  REDIS_HOST: z.string().default("localhost"),
  REDIS_PORT: z.coerce.number().int().positive().default(6379),
  RATE_LIMIT_ENABLED: z.coerce.boolean().default(true),
  RATE_LIMIT_BACKEND: z.enum(["redis", "memory"]).default("redis"),
  RATE_LIMIT_ANONYMOUS_PER_MINUTE: z.coerce.number().int().positive().default(300),
  RATE_LIMIT_AUTHENTICATED_PER_MINUTE: z.coerce.number().int().positive().default(600),
  RATE_LIMIT_TRUSTED_PROXIES: z.string().default("127.0.0.1,::1"),
  ALLOWED_ORIGINS: z.string().default("http://localhost:3000"),
  APP_FRONTEND_URL: z.string().default("http://localhost:3000"),
  SESSION_SECRET: z.string().optional(),
  SESSION_TIMEOUT: z.string().default("30d"),
  SESSION_COOKIE_MAX_AGE: z.string().optional(),
  GOOGLE_CLIENT_ID: z.string().optional(),
  GOOGLE_CLIENT_SECRET: z.string().optional(),
  GOOGLE_PROVIDER_OAUTH_ENABLED: z.coerce.boolean().default(true),
  GOOGLE_PROVIDER_OAUTH_CLIENT_ID: z.string().optional(),
  GOOGLE_PROVIDER_OAUTH_CLIENT_SECRET: z.string().optional(),
  GOOGLE_PROVIDER_OAUTH_USER_PROJECT: z.string().optional(),
  GOOGLE_PROVIDER_OAUTH_AUTHORIZATION_URI: z
    .string()
    .default("https://accounts.google.com/o/oauth2/v2/auth"),
  GOOGLE_PROVIDER_OAUTH_TOKEN_URI: z.string().default("https://oauth2.googleapis.com/token"),
  GOOGLE_PROVIDER_OAUTH_SCOPES: z
    .string()
    .default(
      "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/generative-language.retriever",
    ),
  GITHUB_COPILOT_OAUTH_CLIENT_ID: z.string().optional(),
  GITHUB_COPILOT_OAUTH_SCOPES: z.string().default("read:user"),
  GITHUB_COPILOT_DEVICE_CODE_URI: z.string().default("https://github.com/login/device/code"),
  GITHUB_COPILOT_ACCESS_TOKEN_URI: z
    .string()
    .default("https://github.com/login/oauth/access_token"),
  ENCRYPTION_SECRET: z.string().optional(),
  ENCRYPTION_ACTIVE_KEY_VERSION: z.coerce.number().int().positive().default(1),
  ENCRYPTION_DECRYPTION_SECRETS: z.string().optional(),
  OPENROUTER_API_KEY: z.string().optional(),
  OPENROUTER_BASE_URL: z.string().default("https://openrouter.ai/api/v1"),
  OPENROUTER_MODEL_CATALOG_URL: z.string().default("https://openrouter.ai/api/v1/models"),
  OPENROUTER_APP_NAME: z.string().default("chat-sribiddi"),
  OPENROUTER_REFERRER: z.string().optional(),
  CHAT_CONTEXT_MESSAGE_LIMIT: z.coerce.number().int().positive().default(20),
  CHAT_MESSAGE_PAGE_SIZE: z.coerce.number().int().positive().default(50),
  CHAT_PAGE_SIZE: z.coerce.number().int().positive().default(50),
  CHAT_STREAM_TIMEOUT_MS: z.coerce.number().int().positive().default(1_800_000),
  CHAT_MAX_MEDIA_ATTACHMENTS: z.coerce.number().int().nonnegative().default(5),
  CHAT_MAX_MEDIA_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(5_242_880),
  CHAT_MAX_MEDIA_TOTAL_BYTES: z.coerce.number().int().positive().default(20_971_520),
  ACCESS_AUTH_PARALLEL_STREAMS: z.coerce.number().int().positive().default(1),
  ACCESS_AUTH_CONTEXT_MESSAGE_LIMIT: z.coerce.number().int().positive().default(20),
  ACCESS_AUTH_MAX_ATTACHMENTS: z.coerce.number().int().nonnegative().default(5),
  ACCESS_AUTH_MAX_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(5_242_880),
  ACCESS_AUTH_MAX_TOTAL_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(20_971_520),
  ACCESS_GUEST_CONTEXT_MESSAGE_LIMIT: z.coerce.number().int().positive().default(10),
  ACCESS_GUEST_MAX_ATTACHMENTS: z.coerce.number().int().nonnegative().default(2),
  ACCESS_GUEST_MAX_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(2_097_152),
  ACCESS_GUEST_MAX_TOTAL_ATTACHMENT_BYTES: z.coerce.number().int().positive().default(4_194_304),
  GUEST_OPENROUTER_FREE_DAILY_CAP: z.coerce.number().int().nonnegative().default(20),
  WEB_SEARCH_MAX_RESULTS: z.coerce.number().int().positive().default(5),
  WEB_SEARCH_REQUEST_TIMEOUT: z.string().default("10s"),
  WEB_SEARCH_USER_AGENT: z.string().default("ChatSribiddi/1.0 (WebSearch)"),
  TOOLS_USER_AGENT: z.string().default("ChatSribiddi/1.0 (Tool)"),
  PROVIDER_GATEWAY_ENABLED: z.coerce.boolean().default(false),
  PROVIDER_GATEWAY_BASE_URL: z.string().default("http://127.0.0.1:8095"),
});

const raw = envSchema.parse(process.env);
const isProduction = raw.NODE_ENV === "production" || raw.NODE_ENV === "prod";
const databaseUrl =
  raw.DATABASE_URL ??
  jdbcToPgUrl(raw.SPRING_DATASOURCE_URL) ??
  `postgresql://${encodeURIComponent(raw.POSTGRES_USER ?? raw.SPRING_DATASOURCE_USERNAME ?? "postgres")}:${encodeURIComponent(raw.POSTGRES_PASSWORD ?? raw.SPRING_DATASOURCE_PASSWORD ?? "postgres")}@${raw.POSTGRES_HOST}:${raw.POSTGRES_PORT}/${raw.POSTGRES_DB}`;

const encryptionSecret = raw.ENCRYPTION_SECRET ?? (isProduction ? undefined : randomDevSecret());
const sessionSecret =
  raw.SESSION_SECRET ?? encryptionSecret ?? (isProduction ? undefined : randomDevSecret());

if (!encryptionSecret) {
  throw new Error("ENCRYPTION_SECRET must be configured in production");
}
if (!sessionSecret) {
  throw new Error("SESSION_SECRET or ENCRYPTION_SECRET must be configured in production");
}

export const env = {
  ...raw,
  isProduction,
  isDevProfile: raw.NODE_ENV === "development" || raw.NODE_ENV === "dev" || raw.NODE_ENV === "test",
  databaseUrl,
  encryptionSecret,
  sessionSecret,
  allowedOrigins: raw.ALLOWED_ORIGINS.split(",")
    .map((item) => item.trim())
    .filter(Boolean),
  trustedProxies: raw.RATE_LIMIT_TRUSTED_PROXIES.split(",")
    .map((item) => item.trim())
    .filter(Boolean),
  sessionMaxAgeMs: durationMs(raw.SESSION_COOKIE_MAX_AGE ?? raw.SESSION_TIMEOUT, 30 * 86_400_000),
  webSearchTimeoutMs: durationMs(raw.WEB_SEARCH_REQUEST_TIMEOUT, 10_000),
};

export type AppEnv = typeof env;
