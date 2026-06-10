import { createHash, randomBytes } from "node:crypto";
import { env } from "../config/env.js";
import type { Provider, ProviderConnectionRow } from "../db/types.js";
import { badRequest, notFound } from "../errors/http-error.js";
import {
  connectionDefaultModelId,
  type ProviderConnectionRepository,
  type ProviderConnectionSecret,
} from "../repositories/provider-connection-repository.js";
import { fetchWithTimeout } from "../utils/fetch.js";
import { hasText, legacyToProviderId } from "../utils/strings.js";
import { toIso } from "../utils/time.js";
import { type CodexCliModel, CodexCliService } from "./codex-cli-service.js";
import type { CryptoService } from "./crypto-service.js";
import { type GrokCliBridge, GrokCliUnavailableError } from "./grok-cli-bridge-service.js";
import type {
  ProviderAuthMethod,
  ProviderCatalogService,
  ProviderConnectionProfile,
} from "./provider-catalog-service.js";

const API_KEY_COMPATIBLE_METHODS = ["API_KEY", "OAUTH_PKCE", "OPENAI_COMPATIBLE"];
const CREDENTIAL_SAVE_METHODS: ProviderAuthMethod[] = [
  "API_KEY",
  "OPENAI_COMPATIBLE",
  "LOCAL_ENDPOINT",
  "CUSTOM_HTTP",
];
const TOKEN_REFRESH_SKEW_MS = 2 * 60_000;
const GITHUB_DEVICE_POLL_SLOW_DOWN_SECONDS = 5;
const GITHUB_OAUTH_TIMEOUT_MS = 15_000;
const OPENROUTER_OAUTH_TIMEOUT_MS = 15_000;
const GOOGLE_OAUTH_TIMEOUT_MS = 15_000;
const CODEX_MODEL_SUMMARY_TTL_MS = 30_000;

export type ResolvedProviderConnection = {
  connectionId: number;
  providerId: string;
  connectionProfileId: string;
  authMethod: string;
  apiKey?: string | null | undefined;
  baseUrl?: string | null | undefined;
  headers?: Record<string, string> | undefined;
  queryParams?: Record<string, string> | undefined;
  modelId?: string | null | undefined;
  accessToken?: string | null | undefined;
  refreshToken?: string | null | undefined;
  clientId?: string | null | undefined;
  clientSecret?: string | null | undefined;
  quotaProjectId?: string | null | undefined;
  tokenExpiresAt?: Date | null | undefined;
  scopes?: string[] | undefined;
  codexHome?: string | null | undefined;
  cliHomeDir?: string | null | undefined;
  cliSessionId?: string | null | undefined;
  cliBridge?: "grok-build" | null | undefined;
};

type PendingOAuthState = {
  userId: string;
  providerId: string;
  connectionProfileId: string;
  codeVerifier: string;
  redirectUri: string;
  createdAt: number;
};

type PendingDeviceState = {
  userId: string;
  providerId: string;
  connectionProfileId: string;
  authMethod: ProviderAuthMethod;
  createdAt: number;
  expiresAt: number;
  intervalSeconds: number;
};

type GoogleTokenResponse = {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  scope?: string;
  token_type?: string;
};

type GitHubDeviceCodeResponse = {
  device_code?: string;
  user_code?: string;
  verification_uri?: string;
  verification_uri_complete?: string;
  expires_in?: number;
  interval?: number;
  error?: string;
  error_description?: string;
};

type GitHubTokenResponse = {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  refresh_token_expires_in?: number;
  scope?: string;
  token_type?: string;
  error?: string;
  error_description?: string;
};

type ConnectionSummaryDto = {
  id: number;
  providerId: string;
  connectionProfileId: string;
  authMethod: string;
  status: string;
  displayName: string | null;
  providerAccountLabel: string | null;
  defaultConnection: boolean;
  tokenExpiresAt: string | null;
  lastValidatedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  defaultModelId: string | null;
  availableModels: CodexCliModel[];
};

type CachedConnectionSummary = {
  expiresAt: number;
  rowHash: string;
  summary: ConnectionSummaryDto;
};

export class ProviderConnectionService {
  private readonly oauthStates = new Map<string, PendingOAuthState>();
  private readonly deviceStates = new Map<string, PendingDeviceState>();
  private readonly connectionSummaryCache = new Map<number, CachedConnectionSummary>();
  private readonly connectionSummaryInFlight = new Map<number, Promise<ConnectionSummaryDto>>();

  constructor(
    private readonly repository: ProviderConnectionRepository,
    private readonly providerCatalog: ProviderCatalogService,
    private readonly crypto: CryptoService,
    private readonly grokCli?: GrokCliBridge | undefined,
    private readonly codexCli: CodexCliService = new CodexCliService(),
  ) {}

  private pruneExpiredPendingStates(now = Date.now()) {
    const oauthTtlMs = 15 * 60_000;
    for (const [state, pending] of this.oauthStates) {
      if (now - pending.createdAt > oauthTtlMs) {
        this.oauthStates.delete(state);
      }
    }
    for (const [deviceCode, pending] of this.deviceStates) {
      if (now > pending.expiresAt) {
        this.deviceStates.delete(deviceCode);
      }
    }
  }

  async listConnections(userId: string) {
    const rows = await this.repository.listByUser(userId);
    return Promise.all(rows.map((row) => this.connectionSummary(row)));
  }

  async listCatalogWithStatus(userId: string | null) {
    const connections = userId ? await this.listConnections(userId) : [];
    const byProvider = new Map<string, typeof connections>();
    for (const connection of connections) {
      const list = byProvider.get(connection.providerId) ?? [];
      list.push(connection);
      byProvider.set(connection.providerId, list);
    }
    return this.providerCatalog.listProviders().map((descriptor) => {
      const providerConnections = byProvider.get(descriptor.id) ?? [];
      const ready = providerConnections.some((item) => item.status === "READY");
      return {
        id: descriptor.id,
        label: descriptor.label,
        category: descriptor.category,
        chatProvider: descriptor.chatProvider,
        localOnly: descriptor.localOnly,
        experimental: descriptor.experimental,
        execution: descriptor.execution,
        status: ready ? "READY" : descriptor.defaultStatus,
        authMethods: descriptor.authMethods,
        connectionProfiles: descriptor.connectionProfiles,
        connections: providerConnections,
      };
    });
  }

  async saveApiKey(userId: string, provider: Provider | string, apiKey: string) {
    if (!hasText(apiKey)) throw badRequest("API key must not be empty");
    return this.saveCredential(userId, legacyToProviderId(provider), {
      apiKey,
      authMethod: "API_KEY",
    });
  }

  async saveCredential(
    userId: string,
    providerIdRaw: string,
    payload: {
      apiKey?: string | null | undefined;
      baseUrl?: string | null | undefined;
      authMethod?: ProviderAuthMethod | null | undefined;
      headers?: Record<string, string> | null | undefined;
      queryParams?: Record<string, string> | null | undefined;
      defaultModelId?: string | null | undefined;
      displayName?: string | null | undefined;
      connectionProfileId?: string | null | undefined;
    },
  ) {
    const provider = this.providerCatalog.find(providerIdRaw);
    if (!provider) throw badRequest(`Unknown provider: ${providerIdRaw}`);
    const connectionProfile = credentialProfile(
      provider.connectionProfiles,
      payload.connectionProfileId,
      payload.authMethod,
      payload.baseUrl,
    );
    const authMethod = connectionProfile.authMethod;
    if (!CREDENTIAL_SAVE_METHODS.includes(authMethod)) {
      throw badRequest(`${authMethod} connections must use the dedicated connection flow`);
    }

    const secret: ProviderConnectionSecret = {};
    if (hasText(payload.apiKey)) secret.apiKey = payload.apiKey.trim();
    const endpoint = firstNonBlank(payload.baseUrl, connectionProfile.defaultBaseUrl);
    if (endpoint) secret.baseUrl = normalizeEndpointUrl(endpoint);
    if (hasText(payload.defaultModelId)) secret.modelId = payload.defaultModelId.trim();
    const headers = cleanStringMap(payload.headers ?? undefined);
    if (Object.keys(headers).length > 0) secret.headers = headers;
    const queryParams = cleanStringMap(payload.queryParams ?? undefined);
    if (Object.keys(queryParams).length > 0) secret.queryParams = queryParams;

    if (!secret.apiKey && !secret.baseUrl) {
      throw badRequest("Provider credential must include an API key or endpoint");
    }
    if (connectionProfile.requiresApiKey && !secret.apiKey) {
      throw badRequest("API key must not be empty");
    }
    if (
      (connectionProfile.defaultBaseUrl || connectionProfile.baseUrlEditable) &&
      !secret.baseUrl
    ) {
      throw badRequest("Provider endpoint URL is required");
    }
    if (connectionProfile.requiresModelId && !secret.modelId) {
      throw badRequest("Provider model ID is required");
    }

    return this.saveSecret({
      userId,
      providerId: provider.id,
      connectionProfileId: connectionProfile.id,
      authMethod,
      status: "READY",
      displayName: payload.displayName?.trim() || `${provider.label} (${connectionProfile.label})`,
      secret,
      defaultModelId: secret.modelId ?? null,
    });
  }

  async getApiKey(userId: string, providerIdRaw: string) {
    const providerId = this.providerCatalog.canonicalProviderId(providerIdRaw);
    const row = await this.repository.findReady(userId, providerId, {
      authMethods: API_KEY_COMPATIBLE_METHODS,
    });
    if (!row?.encrypted_secret_json) return null;
    const secret = await this.decryptSecret(row.encrypted_secret_json);
    return secret.apiKey ?? null;
  }

  async hasReadyConnection(userId: string, providerIdRaw: string) {
    return this.repository.hasReady(
      userId,
      this.providerCatalog.canonicalProviderId(providerIdRaw),
    );
  }

  async resolveReadyConnection(
    userId: string,
    providerIdRaw: string,
    connectionId?: number | null,
  ) {
    const providerId = this.providerCatalog.canonicalProviderId(providerIdRaw);
    const row = await this.repository.findReady(
      userId,
      providerId,
      connectionId == null ? {} : { connectionId },
    );
    if (!row) return null;
    const ready = (await this.refreshIfNeeded(row)) ?? row;
    const secret = ready.encrypted_secret_json
      ? await this.decryptSecret(ready.encrypted_secret_json)
      : {};
    const codexDefaultModelId =
      ready.auth_method === "SUBSCRIPTION_CLI"
        ? await this.defaultCodexModelId(secret, ready)
        : null;
    return {
      connectionId: ready.id,
      providerId: ready.provider_id,
      connectionProfileId: ready.connection_profile_id,
      authMethod: ready.auth_method,
      apiKey: secret.apiKey ?? null,
      baseUrl: secret.baseUrl ?? null,
      headers: secret.headers,
      queryParams: secret.queryParams,
      modelId: codexDefaultModelId ?? secret.modelId ?? ready.provider_account_label ?? null,
      accessToken: secret.accessToken ?? null,
      refreshToken: secret.refreshToken ?? null,
      clientId: secret.clientId ?? null,
      clientSecret: secret.clientSecret ?? null,
      quotaProjectId: secret.quotaProjectId ?? null,
      tokenExpiresAt: ready.token_expires_at ?? null,
      scopes: scopesFrom(secret.scopes, ready.scopes),
      codexHome: secret.codexHome ?? null,
      cliHomeDir: secret.cliHomeDir ?? null,
      cliSessionId: secret.cliSessionId ?? null,
      cliBridge: secret.cliBridge ?? null,
    } satisfies ResolvedProviderConnection;
  }

  async deleteApiKey(userId: string, providerIdRaw: string) {
    await this.repository.deleteApiKeyMethods(
      userId,
      this.providerCatalog.canonicalProviderId(providerIdRaw),
    );
  }

  async deleteConnection(userId: string, connectionId: number) {
    const existing = await this.repository.findByIdAndUser(connectionId, userId);
    if (!existing) throw notFound("Provider connection not found");
    if (existing.auth_method === "SUBSCRIPTION_CLI") {
      const secret = existing.encrypted_secret_json
        ? await this.decryptSecret(existing.encrypted_secret_json)
        : {};
      await this.codexCli.deleteCodexHome(secret.codexHome);
    }
    await this.repository.deleteConnection(userId, connectionId);
    this.connectionSummaryCache.delete(connectionId);
  }

  deleteByUser(userId: string) {
    return this.repository.deleteByUser(userId);
  }

  async startConnection(
    userId: string,
    providerIdRaw: string,
    authMethod: ProviderAuthMethod | null | undefined,
    connectionProfileId: string | null | undefined,
    callbackUri: string,
  ) {
    const provider = this.providerCatalog.find(providerIdRaw);
    if (!provider) throw badRequest(`Unknown provider: ${providerIdRaw}`);
    const connectionProfile = connectionProfileForAuth(
      provider.connectionProfiles,
      connectionProfileId,
      authMethod,
    );
    const method = connectionProfile.authMethod;
    if (provider.id === "openrouter" && method === "OAUTH_PKCE") {
      return this.startOpenRouterPkce(userId, callbackUri, connectionProfile.id);
    }
    if (provider.id === "google" && method === "OAUTH_AUTH_CODE") {
      return this.startGoogleOAuth(userId, callbackUri, connectionProfile.id);
    }
    if (provider.id === "github-copilot" && method === "DEVICE_CODE") {
      return this.startGitHubDeviceFlow(userId, connectionProfile);
    }
    if (
      provider.id === "openai" &&
      method === "SUBSCRIPTION_CLI" &&
      connectionProfile.id === "codex-subscription"
    ) {
      const started = await this.codexCli.startDeviceAuth(userId, connectionProfile.id);
      if (started.mode === "UNAVAILABLE") {
        return {
          mode: "UNAVAILABLE",
          authorizationUrl: null,
          state: null,
          expiresAt: null,
          authMethod: method,
          connectionProfileId: connectionProfile.id,
          message: started.message,
        };
      }
      return {
        mode: "CLI_DEVICE_CODE" as const,
        state: started.state,
        deviceCode: started.deviceCode,
        userCode: started.userCode,
        verificationUri: started.verificationUri,
        verificationUriComplete: started.verificationUriComplete,
        expiresAt: started.expiresAt,
        intervalSeconds: started.intervalSeconds,
        authMethod: method,
        connectionProfileId: connectionProfile.id,
        authorizationUrl: started.verificationUriComplete ?? started.verificationUri,
        message: started.message,
      };
    }
    if (provider.id === "xai" && method === "SUBSCRIPTION_CLI") {
      return this.startXaiGrokSubscriptionFlow(userId, connectionProfile);
    }
    return {
      mode: "UNAVAILABLE",
      authorizationUrl: null,
      state: null,
      expiresAt: null,
      authMethod: method,
      connectionProfileId: connectionProfile.id,
      message: "This provider auth flow is cataloged but not enabled yet.",
    };
  }

  async completeOpenRouterPkce(userId: string, state: string | null, code: string | null) {
    if (!hasText(state) || !hasText(code)) {
      throw badRequest("Missing OpenRouter OAuth state or code");
    }
    const pending = this.consumeOAuthState(userId, "openrouter", state);
    const response = await fetchWithTimeout("https://openrouter.ai/api/v1/auth/keys", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        code,
        code_verifier: pending.codeVerifier,
        code_challenge_method: "S256",
      }),
      timeoutMs: OPENROUTER_OAUTH_TIMEOUT_MS,
    });
    if (!response.ok) {
      throw badRequest(`OpenRouter OAuth failed with HTTP ${response.status}`);
    }
    const json = (await response.json()) as { key?: string };
    if (!hasText(json.key)) {
      throw badRequest("OpenRouter did not return a user key");
    }
    return this.saveSecret({
      userId,
      providerId: "openrouter",
      connectionProfileId: pending.connectionProfileId,
      authMethod: "OAUTH_PKCE",
      status: "READY",
      displayName: "OpenRouter OAuth",
      secret: { apiKey: json.key.trim() },
    });
  }

  async completeGoogleOAuth(userId: string, state: string | null, code: string | null) {
    if (!hasText(state) || !hasText(code)) {
      throw badRequest("Missing Google OAuth state or code");
    }
    const pending = this.consumeOAuthState(userId, "google", state);
    const google = googleOAuthConfig();
    if (!google.configured) throw badRequest("Google provider OAuth is not configured");
    const token = await exchangeGoogleToken(
      new URLSearchParams({
        code,
        client_id: google.clientId,
        client_secret: google.clientSecret,
        redirect_uri: pending.redirectUri,
        grant_type: "authorization_code",
        code_verifier: pending.codeVerifier,
      }),
    );
    if (!hasText(token.access_token)) {
      throw badRequest("Google did not return an access token");
    }
    if (!hasText(token.refresh_token)) {
      throw badRequest("Google did not return a refresh token");
    }
    const scopes = tokenScopes(token.scope, google.scopes);
    const secret: ProviderConnectionSecret = {
      accessToken: token.access_token.trim(),
      refreshToken: token.refresh_token.trim(),
      clientId: google.clientId,
      clientSecret: google.clientSecret,
      tokenType: firstNonBlank(token.token_type, "Bearer") ?? "Bearer",
      scopes,
    };
    if (hasText(google.userProject)) {
      secret.quotaProjectId = google.userProject;
    }
    return this.saveSecret({
      userId,
      providerId: "google",
      connectionProfileId: pending.connectionProfileId,
      authMethod: "OAUTH_AUTH_CODE",
      status: "READY",
      displayName: "Google OAuth",
      tokenExpiresAt: expiresAt(token.expires_in),
      scopes,
      secret,
    });
  }

  async completeDeviceConnection(
    userId: string,
    providerIdRaw: string,
    payload: {
      deviceCode?: string | null | undefined;
      connectionProfileId?: string | null | undefined;
    },
  ) {
    const provider = this.providerCatalog.find(providerIdRaw);
    if (!provider) throw badRequest(`Unknown provider: ${providerIdRaw}`);
    if (provider.id === "xai") {
      return this.completeXaiGrokSubscriptionConnection(
        userId,
        provider.id,
        provider.connectionProfiles,
        payload,
      );
    }
    if (!hasText(payload.deviceCode)) {
      throw badRequest("Missing device code");
    }
    const deviceCode = payload.deviceCode.trim();
    if (provider.id === "openai") {
      return this.completeOpenAiCodexDeviceConnection(
        userId,
        deviceCode,
        payload.connectionProfileId,
      );
    }
    if (provider.id !== "github-copilot") {
      throw badRequest("Device-code flow is not enabled for this provider");
    }
    const pending = this.deviceStates.get(deviceCode);
    if (
      !pending ||
      pending.userId !== userId ||
      pending.providerId !== provider.id ||
      (hasText(payload.connectionProfileId) &&
        pending.connectionProfileId !== payload.connectionProfileId.trim()) ||
      pending.expiresAt < Date.now()
    ) {
      this.deviceStates.delete(deviceCode);
      throw badRequest("Device-code state is invalid or expired");
    }

    const github = githubCopilotOAuthConfig();
    if (!github.configured) {
      return {
        mode: "UNAVAILABLE",
        authMethod: pending.authMethod,
        connectionProfileId: pending.connectionProfileId,
        message: "GitHub Copilot OAuth is not configured.",
      };
    }
    const token = await exchangeGitHubDeviceToken(
      new URLSearchParams({
        client_id: github.clientId,
        device_code: deviceCode,
        grant_type: "urn:ietf:params:oauth:grant-type:device_code",
      }),
    );

    if (token.error === "authorization_pending") {
      return {
        mode: "PENDING",
        authMethod: pending.authMethod,
        connectionProfileId: pending.connectionProfileId,
        expiresAt: new Date(pending.expiresAt).toISOString(),
        intervalSeconds: pending.intervalSeconds,
        message: token.error_description ?? "Authorization is still pending.",
      };
    }
    if (token.error === "slow_down") {
      pending.intervalSeconds += GITHUB_DEVICE_POLL_SLOW_DOWN_SECONDS;
      return {
        mode: "PENDING",
        authMethod: pending.authMethod,
        connectionProfileId: pending.connectionProfileId,
        expiresAt: new Date(pending.expiresAt).toISOString(),
        intervalSeconds: pending.intervalSeconds,
        message: token.error_description ?? "Polling slowed down by GitHub.",
      };
    }
    if (token.error) {
      this.deviceStates.delete(deviceCode);
      throw badRequest(token.error_description ?? `GitHub device flow failed: ${token.error}`);
    }
    if (!hasText(token.access_token)) {
      throw badRequest("GitHub did not return an access token");
    }

    this.deviceStates.delete(deviceCode);
    const secret: ProviderConnectionSecret = {
      accessToken: token.access_token.trim(),
      tokenType: firstNonBlank(token.token_type, "Bearer") ?? "Bearer",
      scopes: tokenScopes(token.scope, github.scopes),
    };
    if (hasText(token.refresh_token)) secret.refreshToken = token.refresh_token.trim();
    const scopes = secret.scopes ?? null;
    const connection = await this.saveSecret({
      userId,
      providerId: provider.id,
      connectionProfileId: pending.connectionProfileId,
      authMethod: "DEVICE_CODE",
      status: "READY",
      displayName: "GitHub Copilot OAuth",
      providerAccountLabel: "GitHub OAuth",
      tokenExpiresAt: expiresAt(token.expires_in),
      scopes,
      secret,
    });
    return {
      mode: "READY",
      authMethod: "DEVICE_CODE" as const,
      connectionProfileId: pending.connectionProfileId,
      connection,
      message: null,
    };
  }

  private async completeOpenAiCodexDeviceConnection(
    userId: string,
    state: string,
    connectionProfileId: string | null | undefined,
  ) {
    const completed = await this.codexCli.completeDeviceAuth(userId, state, connectionProfileId);
    if (completed.mode === "PENDING") {
      return {
        mode: "PENDING" as const,
        authMethod: "SUBSCRIPTION_CLI" as const,
        connectionProfileId: connectionProfileId?.trim() || "codex-subscription",
        expiresAt: completed.expiresAt,
        intervalSeconds: completed.intervalSeconds,
        message: completed.message,
      };
    }
    if (completed.mode === "UNAVAILABLE") {
      return {
        mode: "UNAVAILABLE" as const,
        authMethod: "SUBSCRIPTION_CLI" as const,
        connectionProfileId: connectionProfileId?.trim() || "codex-subscription",
        message: completed.message,
      };
    }
    const secret: ProviderConnectionSecret = {
      codexHome: completed.codexHome,
      modelId: completed.modelId,
      models: completed.models,
    };
    const connection = await this.saveSecret({
      userId,
      providerId: "openai",
      connectionProfileId: connectionProfileId?.trim() || "codex-subscription",
      authMethod: "SUBSCRIPTION_CLI",
      status: "READY",
      displayName: "OpenAI (ChatGPT/Codex subscription)",
      providerAccountLabel: completed.modelId,
      secret,
    });
    return {
      mode: "READY" as const,
      authMethod: "SUBSCRIPTION_CLI" as const,
      connectionProfileId: connection.connectionProfileId,
      connection,
      message: completed.message,
    };
  }

  private async decryptSecret(value: string): Promise<ProviderConnectionSecret> {
    const decrypted = await this.crypto.decrypt(value);
    try {
      return JSON.parse(decrypted) as ProviderConnectionSecret;
    } catch {
      return { apiKey: decrypted };
    }
  }

  private async connectionSummary(row: ProviderConnectionRow) {
    const rowHash = hashConnectionRow(row);
    const cached = this.connectionSummaryCache.get(row.id);
    if (cached && cached.expiresAt > Date.now() && cached.rowHash === rowHash) {
      return cached.summary;
    }
    const inFlight = this.connectionSummaryInFlight.get(row.id);
    if (inFlight) return inFlight;
    const promise = this.buildConnectionSummary(row, rowHash);
    this.connectionSummaryInFlight.set(row.id, promise);
    try {
      return await promise;
    } finally {
      this.connectionSummaryInFlight.delete(row.id);
    }
  }

  private async buildConnectionSummary(
    row: ProviderConnectionRow,
    rowHash: string,
  ): Promise<ConnectionSummaryDto> {
    const availableModels = await this.availableCodexModels(row);
    const defaultModelId =
      row.auth_method === "SUBSCRIPTION_CLI"
        ? (availableModels[0]?.id ?? connectionDefaultModelId(row))
        : connectionDefaultModelId(row);
    const summary: ConnectionSummaryDto = {
      id: row.id,
      providerId: row.provider_id,
      connectionProfileId: row.connection_profile_id,
      authMethod: row.auth_method,
      status: row.status,
      displayName: row.display_name,
      providerAccountLabel: row.provider_account_label,
      defaultConnection: row.default_connection,
      tokenExpiresAt: toIso(row.token_expires_at),
      lastValidatedAt: toIso(row.last_validated_at),
      createdAt: toIso(row.created_at),
      updatedAt: toIso(row.updated_at),
      defaultModelId,
      availableModels,
    };
    this.connectionSummaryCache.set(row.id, {
      expiresAt: Date.now() + CODEX_MODEL_SUMMARY_TTL_MS,
      rowHash,
      summary,
    });
    return summary;
  }

  private async availableCodexModels(row: ProviderConnectionRow): Promise<CodexCliModel[]> {
    if (
      row.auth_method !== "SUBSCRIPTION_CLI" ||
      row.connection_profile_id !== "codex-subscription" ||
      row.status !== "READY" ||
      !row.encrypted_secret_json
    ) {
      return [];
    }
    const secret = await this.decryptSecret(row.encrypted_secret_json);
    const saved = normalizeCodexModels(secret.models);
    if (!this.codexCli.enabled()) return saved;
    if (!hasText(secret.codexHome)) return saved;
    try {
      const live = await this.codexCli.listModels(secret.codexHome.trim());
      return live.length > 0 ? live : saved;
    } catch {
      return saved;
    }
  }

  private async defaultCodexModelId(
    secret: ProviderConnectionSecret,
    row: ProviderConnectionRow,
  ): Promise<string | null> {
    if (row.connection_profile_id !== "codex-subscription") return null;
    const saved = normalizeCodexModels(secret.models);
    if (!this.codexCli.enabled())
      return saved[0]?.id ?? secret.modelId ?? row.provider_account_label ?? null;
    if (!hasText(secret.codexHome)) {
      return saved[0]?.id ?? secret.modelId ?? row.provider_account_label ?? null;
    }
    try {
      const live = await this.codexCli.listModels(secret.codexHome.trim());
      return live[0]?.id ?? saved[0]?.id ?? secret.modelId ?? row.provider_account_label ?? null;
    } catch {
      return saved[0]?.id ?? secret.modelId ?? row.provider_account_label ?? null;
    }
  }

  private async saveSecret(input: {
    userId: string;
    providerId: string;
    connectionProfileId: string;
    authMethod: ProviderAuthMethod;
    status: string;
    displayName: string | null;
    secret: ProviderConnectionSecret;
    defaultModelId?: string | null;
    tokenExpiresAt?: Date | null;
    scopes?: string[] | null;
    providerAccountId?: string | null;
    providerAccountLabel?: string | null;
  }) {
    const row = await this.repository.upsert({
      userId: input.userId,
      providerId: input.providerId,
      connectionProfileId: input.connectionProfileId,
      authMethod: input.authMethod,
      status: input.status,
      displayName: input.displayName,
      encryptedSecretJson: this.crypto.encrypt(JSON.stringify(input.secret)),
      keyVersion: this.crypto.activeKeyVersion(),
      defaultModelId: input.defaultModelId ?? null,
      tokenExpiresAt: input.tokenExpiresAt ?? null,
      scopes: input.scopes?.length ? input.scopes.join(" ") : null,
      providerAccountId: input.providerAccountId ?? null,
      providerAccountLabel: input.providerAccountLabel ?? null,
    });
    this.connectionSummaryCache.delete(row.id);
    return this.connectionSummary(row);
  }

  private startOpenRouterPkce(userId: string, callbackUri: string, connectionProfileId: string) {
    this.pruneExpiredPendingStates();
    const state = randomToken(32);
    const verifier = randomToken(64);
    const challenge = sha256Base64Url(verifier);
    const callback = new URL(callbackUri);
    callback.searchParams.set("state", state);
    this.oauthStates.set(state, {
      userId,
      providerId: "openrouter",
      connectionProfileId,
      codeVerifier: verifier,
      redirectUri: callback.toString(),
      createdAt: Date.now(),
    });
    const authorization = new URL("https://openrouter.ai/auth");
    authorization.searchParams.set("callback_url", callback.toString());
    authorization.searchParams.set("code_challenge", challenge);
    authorization.searchParams.set("code_challenge_method", "S256");
    return {
      mode: "REDIRECT",
      authorizationUrl: authorization.toString(),
      state,
      expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      authMethod: "OAUTH_PKCE" as const,
      connectionProfileId,
      message: null,
    };
  }

  private startGoogleOAuth(userId: string, callbackUri: string, connectionProfileId: string) {
    this.pruneExpiredPendingStates();
    const google = googleOAuthConfig();
    if (!google.configured) {
      return {
        mode: "UNAVAILABLE",
        authorizationUrl: null,
        state: null,
        expiresAt: null,
        authMethod: "OAUTH_AUTH_CODE" as const,
        connectionProfileId,
        message: "Google provider OAuth is not configured.",
      };
    }
    const state = randomToken(32);
    const verifier = randomToken(64);
    const challenge = sha256Base64Url(verifier);
    this.oauthStates.set(state, {
      userId,
      providerId: "google",
      connectionProfileId,
      codeVerifier: verifier,
      redirectUri: callbackUri,
      createdAt: Date.now(),
    });
    const authorization = new URL(google.authorizationUri);
    authorization.searchParams.set("response_type", "code");
    authorization.searchParams.set("client_id", google.clientId);
    authorization.searchParams.set("redirect_uri", callbackUri);
    authorization.searchParams.set("scope", google.scopes.join(" "));
    authorization.searchParams.set("access_type", "offline");
    authorization.searchParams.set("prompt", "consent");
    authorization.searchParams.set("state", state);
    authorization.searchParams.set("code_challenge", challenge);
    authorization.searchParams.set("code_challenge_method", "S256");
    return {
      mode: "REDIRECT",
      authorizationUrl: authorization.toString(),
      state,
      expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      authMethod: "OAUTH_AUTH_CODE" as const,
      connectionProfileId,
      message: null,
    };
  }

  private async startGitHubDeviceFlow(
    userId: string,
    connectionProfile: ProviderConnectionProfile,
  ) {
    this.pruneExpiredPendingStates();
    const github = githubCopilotOAuthConfig();
    if (!github.configured) {
      return {
        mode: "UNAVAILABLE",
        authorizationUrl: null,
        state: null,
        expiresAt: null,
        authMethod: connectionProfile.authMethod,
        connectionProfileId: connectionProfile.id,
        message: "GitHub Copilot OAuth is not configured.",
      };
    }
    const response = await fetchWithTimeout(github.deviceCodeUri, {
      method: "POST",
      headers: {
        accept: "application/json",
        "content-type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        client_id: github.clientId,
        scope: github.scopes.join(" "),
      }),
      timeoutMs: GITHUB_OAUTH_TIMEOUT_MS,
    });
    if (!response.ok) throw badRequest(`GitHub device flow failed with HTTP ${response.status}`);
    const json = (await response.json()) as GitHubDeviceCodeResponse;
    if (json.error) {
      throw badRequest(json.error_description ?? `GitHub device flow failed: ${json.error}`);
    }
    if (!hasText(json.device_code) || !hasText(json.user_code) || !hasText(json.verification_uri)) {
      throw badRequest("GitHub did not return a device-code challenge");
    }
    const expiresAt = Date.now() + Math.max(1, json.expires_in ?? 900) * 1000;
    const intervalSeconds = Math.max(5, json.interval ?? 5);
    this.deviceStates.set(json.device_code.trim(), {
      userId,
      providerId: "github-copilot",
      connectionProfileId: connectionProfile.id,
      authMethod: connectionProfile.authMethod,
      createdAt: Date.now(),
      expiresAt,
      intervalSeconds,
    });
    return {
      mode: "DEVICE_CODE",
      authorizationUrl: json.verification_uri_complete ?? json.verification_uri,
      state: null,
      expiresAt: new Date(expiresAt).toISOString(),
      authMethod: connectionProfile.authMethod,
      connectionProfileId: connectionProfile.id,
      deviceCode: json.device_code,
      userCode: json.user_code,
      verificationUri: json.verification_uri,
      verificationUriComplete: json.verification_uri_complete ?? null,
      intervalSeconds,
      message: null,
    };
  }

  private async startXaiGrokSubscriptionFlow(
    userId: string,
    connectionProfile: ProviderConnectionProfile,
  ) {
    if (!this.grokCli) {
      return {
        mode: "UNAVAILABLE",
        authorizationUrl: null,
        state: null,
        expiresAt: null,
        authMethod: connectionProfile.authMethod,
        connectionProfileId: connectionProfile.id,
        message: "Grok Build subscription bridge is not configured.",
      };
    }
    try {
      const login = await this.grokCli.startDeviceLogin(userId);
      return {
        mode: "DEVICE_CODE",
        authorizationUrl: login.verificationUriComplete ?? login.verificationUri,
        state: null,
        expiresAt: login.expiresAt,
        authMethod: connectionProfile.authMethod,
        connectionProfileId: connectionProfile.id,
        deviceCode: login.loginId,
        userCode: login.userCode,
        verificationUri: login.verificationUri,
        verificationUriComplete: login.verificationUriComplete,
        intervalSeconds: login.intervalSeconds,
        message: null,
      };
    } catch (error) {
      if (error instanceof GrokCliUnavailableError) {
        return {
          mode: "UNAVAILABLE",
          authorizationUrl: null,
          state: null,
          expiresAt: null,
          authMethod: connectionProfile.authMethod,
          connectionProfileId: connectionProfile.id,
          message: error.message,
        };
      }
      throw error;
    }
  }

  private async completeXaiGrokSubscriptionConnection(
    userId: string,
    providerId: string,
    profiles: ProviderConnectionProfile[],
    payload: {
      deviceCode?: string | null | undefined;
      connectionProfileId?: string | null | undefined;
    },
  ) {
    const connectionProfile = connectionProfileForExactAuth(
      profiles,
      payload.connectionProfileId,
      "SUBSCRIPTION_CLI",
    );
    const connectionProfileId = connectionProfile.id;
    if (!this.grokCli) {
      return {
        mode: "UNAVAILABLE",
        authMethod: "SUBSCRIPTION_CLI" as const,
        connectionProfileId,
        message: "Grok Build subscription bridge is not configured.",
      };
    }
    if (!hasText(payload.deviceCode)) {
      throw badRequest("Missing device code");
    }
    try {
      const result = await this.grokCli.pollDeviceLogin(userId, payload.deviceCode.trim());
      if (result.mode === "PENDING") {
        return {
          mode: "PENDING",
          authMethod: "SUBSCRIPTION_CLI" as const,
          connectionProfileId,
          expiresAt: result.expiresAt,
          intervalSeconds: result.intervalSeconds,
          message: result.message,
        };
      }
      const connection = await this.saveSecret({
        userId,
        providerId,
        connectionProfileId,
        authMethod: "SUBSCRIPTION_CLI",
        status: "READY",
        displayName: result.accountLabel,
        defaultModelId: result.session.modelId,
        secret: {
          cliHomeDir: result.session.cliHomeDir,
          cliSessionId: result.session.cliSessionId,
          cliBridge: "grok-build",
          modelId: result.session.modelId,
        },
      });
      return {
        mode: "READY",
        authMethod: "SUBSCRIPTION_CLI" as const,
        connectionProfileId,
        connection,
        message: null,
      };
    } catch (error) {
      if (error instanceof GrokCliUnavailableError) {
        return {
          mode: "UNAVAILABLE",
          authMethod: "SUBSCRIPTION_CLI" as const,
          connectionProfileId,
          message: error.message,
        };
      }
      throw error;
    }
  }

  private consumeOAuthState(userId: string, providerId: string, state: string) {
    const pending = this.oauthStates.get(state);
    this.oauthStates.delete(state);
    if (
      !pending ||
      pending.userId !== userId ||
      pending.providerId !== providerId ||
      pending.createdAt < Date.now() - 10 * 60_000
    ) {
      throw badRequest(`${providerId} OAuth state is invalid or expired`);
    }
    return pending;
  }

  private async refreshIfNeeded(
    row: Awaited<ReturnType<ProviderConnectionRepository["findReady"]>>,
  ) {
    if (row?.auth_method !== "OAUTH_AUTH_CODE" || row.provider_id !== "google") return row;
    if (
      row.token_expires_at &&
      row.token_expires_at.getTime() > Date.now() + TOKEN_REFRESH_SKEW_MS
    ) {
      return row;
    }
    const secret = row.encrypted_secret_json ? await this.decryptSecret(row.encrypted_secret_json) : {};
    if (!hasText(secret.refreshToken)) return row;
    const google = googleOAuthConfig();
    const clientId = firstNonBlank(secret.clientId, google.clientId);
    const clientSecret = firstNonBlank(secret.clientSecret, google.clientSecret);
    if (!hasText(clientId) || !hasText(clientSecret)) return row;
    try {
      const token = await exchangeGoogleToken(
        new URLSearchParams({
          client_id: clientId,
          client_secret: clientSecret,
          refresh_token: secret.refreshToken,
          grant_type: "refresh_token",
        }),
      );
      if (!hasText(token.access_token)) return row;
      const scopes = tokenScopes(token.scope, scopesFrom(secret.scopes, row.scopes));
      const updatedSecret: ProviderConnectionSecret = {
        ...secret,
        accessToken: token.access_token.trim(),
        scopes,
      };
      const refreshToken = firstNonBlank(token.refresh_token, secret.refreshToken);
      if (refreshToken) updatedSecret.refreshToken = refreshToken;
      const tokenType = firstNonBlank(token.token_type, secret.tokenType ?? "Bearer");
      if (tokenType) updatedSecret.tokenType = tokenType;
      const updated = await this.repository.updateSecret({
        connectionId: row.id,
        encryptedSecretJson: this.crypto.encrypt(JSON.stringify(updatedSecret)),
        keyVersion: this.crypto.activeKeyVersion(),
        tokenExpiresAt: expiresAt(token.expires_in),
        scopes: scopes.length ? scopes.join(" ") : null,
      });
      this.connectionSummaryCache.delete(row.id);
      return updated ?? row;
    } catch {
      return row;
    }
  }
}

async function exchangeGoogleToken(body: URLSearchParams): Promise<GoogleTokenResponse> {
  const response = await fetchWithTimeout(googleOAuthConfig().tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
    timeoutMs: GOOGLE_OAUTH_TIMEOUT_MS,
  });
  if (!response.ok) throw badRequest(`Google OAuth failed with HTTP ${response.status}`);
  return (await response.json()) as GoogleTokenResponse;
}

async function exchangeGitHubDeviceToken(body: URLSearchParams): Promise<GitHubTokenResponse> {
  const response = await fetchWithTimeout(githubCopilotOAuthConfig().accessTokenUri, {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/x-www-form-urlencoded",
    },
    body,
    timeoutMs: GITHUB_OAUTH_TIMEOUT_MS,
  });
  if (!response.ok) throw badRequest(`GitHub OAuth failed with HTTP ${response.status}`);
  return (await response.json()) as GitHubTokenResponse;
}

function googleOAuthConfig() {
  const clientId = firstNonBlank(env.GOOGLE_PROVIDER_OAUTH_CLIENT_ID, env.GOOGLE_CLIENT_ID);
  const clientSecret = firstNonBlank(
    env.GOOGLE_PROVIDER_OAUTH_CLIENT_SECRET,
    env.GOOGLE_CLIENT_SECRET,
  );
  const scopes = env.GOOGLE_PROVIDER_OAUTH_SCOPES.split(/[\s,]+/).filter(hasText);
  return {
    configured: env.GOOGLE_PROVIDER_OAUTH_ENABLED && hasText(clientId) && hasText(clientSecret),
    clientId: clientId ?? "",
    clientSecret: clientSecret ?? "",
    userProject: env.GOOGLE_PROVIDER_OAUTH_USER_PROJECT?.trim() || undefined,
    authorizationUri: env.GOOGLE_PROVIDER_OAUTH_AUTHORIZATION_URI,
    tokenUri: env.GOOGLE_PROVIDER_OAUTH_TOKEN_URI,
    scopes: scopes.length
      ? scopes
      : [
          "https://www.googleapis.com/auth/cloud-platform",
          "https://www.googleapis.com/auth/generative-language.retriever",
        ],
  };
}

function githubCopilotOAuthConfig() {
  const scopes = env.GITHUB_COPILOT_OAUTH_SCOPES.split(/[\s,]+/).filter(hasText);
  return {
    configured: hasText(env.GITHUB_COPILOT_OAUTH_CLIENT_ID),
    clientId: env.GITHUB_COPILOT_OAUTH_CLIENT_ID?.trim() ?? "",
    deviceCodeUri: env.GITHUB_COPILOT_DEVICE_CODE_URI,
    accessTokenUri: env.GITHUB_COPILOT_ACCESS_TOKEN_URI,
    scopes: scopes.length ? scopes : ["read:user"],
  };
}

function randomToken(bytes: number) {
  return randomBytes(bytes).toString("base64url");
}

function sha256Base64Url(input: string) {
  return createHash("sha256").update(input).digest("base64url");
}

function expiresAt(expiresInSeconds: number | null | undefined) {
  return expiresInSeconds && expiresInSeconds > 0
    ? new Date(Date.now() + expiresInSeconds * 1000)
    : null;
}

function tokenScopes(scope: string | null | undefined, fallback: string[] = []) {
  return hasText(scope) ? scope.trim().split(/\s+/) : fallback.filter(hasText);
}

function scopesFrom(value: unknown, fallback: string | null | undefined) {
  if (Array.isArray(value))
    return value.filter((item): item is string => typeof item === "string" && hasText(item));
  if (hasText(fallback)) return fallback.trim().split(/\s+/);
  return [];
}

function normalizeCodexModels(value: unknown): CodexCliModel[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item): CodexCliModel | null => {
      if (!item || typeof item !== "object") return null;
      const source = item as Record<string, unknown>;
      const id = text(source.id);
      if (!id) return null;
      return {
        id,
        label: text(source.label) ?? id,
        description: text(source.description),
      };
    })
    .filter((item): item is CodexCliModel => item !== null);
}

function text(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function firstNonBlank(first: string | null | undefined, second: string | null | undefined) {
  return hasText(first) ? first.trim() : hasText(second) ? second.trim() : undefined;
}

function credentialProfile(
  profiles: ProviderConnectionProfile[],
  requestedProfileId: string | null | undefined,
  requested: ProviderAuthMethod | null | undefined,
  baseUrl: string | null | undefined,
): ProviderConnectionProfile {
  if (hasText(requestedProfileId)) {
    const profile = profiles.find((item) => item.id === requestedProfileId.trim());
    if (!profile) throw badRequest(`Unknown connection profile: ${requestedProfileId}`);
    return profile;
  }
  if (requested) {
    const profile = profiles.find(
      (item) => item.authMethod === requested && item.status !== "DISABLED",
    );
    if (!profile) throw badRequest(`Auth method ${requested} is not supported for this provider`);
    return profile;
  }
  if (hasText(baseUrl)) {
    const profile = profiles.find(
      (item) =>
        (item.authMethod === "OPENAI_COMPATIBLE" || item.authMethod === "LOCAL_ENDPOINT") &&
        item.status !== "DISABLED",
    );
    if (profile) return profile;
  }
  return (
    profiles.find((item) => item.id === "api-key" && item.status !== "DISABLED") ??
    profiles.find((item) => item.status !== "DISABLED") ??
    profiles[0] ??
    badConnectionProfile()
  );
}

function connectionProfileForAuth(
  profiles: ProviderConnectionProfile[],
  requestedProfileId: string | null | undefined,
  requested: ProviderAuthMethod | null | undefined,
): ProviderConnectionProfile {
  return credentialProfile(profiles, requestedProfileId, requested, null);
}

function connectionProfileForExactAuth(
  profiles: ProviderConnectionProfile[],
  requestedProfileId: string | null | undefined,
  requested: ProviderAuthMethod,
): ProviderConnectionProfile {
  const profile = connectionProfileForAuth(profiles, requestedProfileId, requested);
  if (profile.authMethod !== requested) {
    throw badRequest(`Connection profile ${profile.id} does not support ${requested}`);
  }
  return profile;
}

function badConnectionProfile(): never {
  throw badRequest("Provider has no connection profiles");
}

function cleanStringMap(value: Record<string, string> | undefined) {
  const result: Record<string, string> = {};
  if (!value) return result;
  for (const [key, item] of Object.entries(value)) {
    if (hasText(key) && hasText(item)) result[key.trim()] = item.trim();
  }
  return result;
}

function normalizeEndpointUrl(value: string) {
  let parsed: URL;
  try {
    parsed = new URL(value.trim());
  } catch {
    throw badRequest("baseUrl must be a valid URL");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw badRequest("baseUrl must use http or https");
  }
  if (parsed.username || parsed.password) {
    throw badRequest("baseUrl must not include credentials");
  }
  if (
    (parsed.hostname === "localhost" || parsed.hostname.endsWith(".localhost")) &&
    env.isProduction
  ) {
    throw badRequest("Local provider endpoints are disabled in production");
  }
  parsed.hash = "";
  parsed.search = "";
  return parsed.toString().replace(/\/$/, "");
}

/**
 * Stable, low-cardinality fingerprint of the fields that influence a connection
 * summary. Used to validate cached summaries; if any tracked field changes
 * between calls (e.g. status flips from PENDING to READY, or `updated_at`
 * advances) the cache entry is rebuilt instead of returning a stale view.
 */
function hashConnectionRow(row: ProviderConnectionRow) {
  const parts = [
    row.status,
    row.display_name ?? "",
    row.provider_account_id ?? "",
    row.provider_account_label ?? "",
    row.token_expires_at instanceof Date ? row.token_expires_at.getTime() : row.token_expires_at ?? "",
    row.last_validated_at instanceof Date ? row.last_validated_at.getTime() : row.last_validated_at ?? "",
    row.updated_at instanceof Date ? row.updated_at.getTime() : row.updated_at ?? "",
    row.auth_method,
  ];
  return createHash("sha1").update(parts.join("|")).digest("hex");
}
