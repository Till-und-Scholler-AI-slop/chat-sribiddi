import { afterEach, describe, expect, it, vi } from "vitest";
import { env } from "../config/env.js";
import type { ProviderConnectionRow } from "../db/types.js";
import { CryptoService } from "./crypto-service.js";
import { ProviderCatalogService } from "./provider-catalog-service.js";
import { ProviderConnectionService } from "./provider-connection-service.js";

const secret = Buffer.alloc(32, 9).toString("base64");

describe("ProviderConnectionService", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("groups catalog-only auth options as connection profiles", () => {
    const catalog = new ProviderCatalogService();

    expect(catalog.find("qwen-oauth")).toBeUndefined();
    expect(catalog.find("minimax-portal")).toBeUndefined();
    expect(catalog.find("qwen")?.connectionProfiles.map((profile) => profile.id)).toEqual([
      "api",
      "coding-plan",
      "portal",
    ]);
    expect(catalog.find("openrouter")?.connectionProfiles).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "openrouter-oauth",
          authMethod: "OAUTH_PKCE",
          oauthEnabled: true,
        }),
      ]),
    );
    expect(catalog.find("openai")?.connectionProfiles).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "codex-subscription",
          authMethod: "SUBSCRIPTION_CLI",
          connectMode: "CLI_DEVICE_CODE",
          oauthEnabled: true,
        }),
      ]),
    );
  });

  it("starts and completes OpenRouter PKCE", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const repository = fakeRepository(rows, crypto);
    const service = new ProviderConnectionService(
      repository as never,
      new ProviderCatalogService(),
      crypto,
    );
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ key: "sk-openrouter-user" })),
    );

    const start = await service.startConnection(
      "user-1",
      "openrouter",
      "OAUTH_PKCE",
      "openrouter-oauth",
      "https://germinai.de/provider-connections/openrouter/callback",
    );

    expect(start.mode).toBe("REDIRECT");
    expect(start.authorizationUrl).toContain("https://openrouter.ai/auth");
    expect(start.authorizationUrl).toContain("code_challenge=");

    const completed = await service.completeOpenRouterPkce("user-1", start.state, "code-123");

    expect(completed.providerId).toBe("openrouter");
    expect(completed.connectionProfileId).toBe("openrouter-oauth");
    expect(completed.authMethod).toBe("OAUTH_PKCE");
    expect(await service.getApiKey("user-1", "openrouter")).toBe("sk-openrouter-user");
  });

  it("keeps old API-key payloads compatible with the default profile", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );

    const saved = await service.saveCredential("user-1", "OPENAI", {
      apiKey: "sk-openai-user",
    });

    expect(saved.providerId).toBe("openai");
    expect(saved.connectionProfileId).toBe("api-key");
    expect(rows).toHaveLength(1);
    expect(rows[0]?.connection_profile_id).toBe("api-key");
  });

  it("fills default profile endpoint URLs when no override is provided", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );

    await service.saveCredential("user-1", "qwen", {
      connectionProfileId: "api",
      apiKey: "sk-qwen",
      defaultModelId: "qwen-max",
    });

    const resolved = await service.resolveReadyConnection("user-1", "qwen");
    expect(resolved?.connectionProfileId).toBe("api");
    expect(resolved?.baseUrl).toBe("https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
    expect(resolved?.modelId).toBe("qwen-max");
  });

  it("preserves manual endpoint overrides per profile", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );

    await service.saveCredential("user-1", "zai", {
      connectionProfileId: "coding-plan",
      apiKey: "sk-zai",
      baseUrl: "https://proxy.example.test/zai/v1",
      defaultModelId: "glm-4.6",
    });

    const resolved = await service.resolveReadyConnection("user-1", "zai");
    expect(resolved?.connectionProfileId).toBe("coding-plan");
    expect(resolved?.baseUrl).toBe("https://proxy.example.test/zai/v1");
    expect(resolved?.modelId).toBe("glm-4.6");
  });

  it("updates an existing provider profile instead of creating auth-method duplicates", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );

    await service.saveCredential("user-1", "deepseek", {
      authMethod: "OPENAI_COMPATIBLE",
      apiKey: "sk-deepseek-a",
      defaultModelId: "deepseek-chat",
    });
    await service.saveCredential("user-1", "deepseek", {
      connectionProfileId: "api",
      apiKey: "sk-deepseek-b",
      defaultModelId: "deepseek-reasoner",
    });

    expect(rows).toHaveLength(1);
    expect(rows[0]?.connection_profile_id).toBe("api");
    expect(rows[0]?.provider_account_label).toBe("deepseek-reasoner");
    expect(await service.getApiKey("user-1", "deepseek")).toBe("sk-deepseek-b");
  });

  it("starts and completes the GitHub Copilot device flow", async () => {
    const previousClientId = env.GITHUB_COPILOT_OAUTH_CLIENT_ID;
    const previousScopes = env.GITHUB_COPILOT_OAUTH_SCOPES;
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );
    env.GITHUB_COPILOT_OAUTH_CLIENT_ID = "github-client";
    env.GITHUB_COPILOT_OAUTH_SCOPES = "read:user copilot";
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL) => {
        if (String(url).includes("device/code")) {
          return Response.json({
            device_code: "device-123",
            user_code: "ABCD-1234",
            verification_uri: "https://github.com/login/device",
            verification_uri_complete: "https://github.com/login/device?user_code=ABCD-1234",
            expires_in: 600,
            interval: 5,
          });
        }
        return Response.json({
          access_token: "ghu-copilot",
          token_type: "bearer",
          scope: "read:user copilot",
          expires_in: 3600,
        });
      }),
    );

    try {
      const start = await service.startConnection(
        "user-1",
        "github-copilot",
        "DEVICE_CODE",
        "github-oauth",
        "https://germinai.de/provider-connections/github-copilot/callback",
      );

      expect(start.mode).toBe("DEVICE_CODE");
      expect(start.connectionProfileId).toBe("github-oauth");
      expect((start as { userCode?: string }).userCode).toBe("ABCD-1234");

      const completed = await service.completeDeviceConnection("user-1", "github-copilot", {
        deviceCode: "device-123",
        connectionProfileId: "github-oauth",
      });

      expect(completed.mode).toBe("READY");
      expect(completed.connection?.connectionProfileId).toBe("github-oauth");
      const resolved = await service.resolveReadyConnection("user-1", "github-copilot");
      expect(resolved?.accessToken).toBe("ghu-copilot");
    } finally {
      env.GITHUB_COPILOT_OAUTH_CLIENT_ID = previousClientId;
      env.GITHUB_COPILOT_OAUTH_SCOPES = previousScopes;
    }
  });

  it("starts and completes OpenAI Codex subscription device login", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows: ProviderConnectionRow[] = [];
    const codexCli = {
      startDeviceAuth: vi.fn(async () => ({
        mode: "DEVICE_CODE" as const,
        state: "codex-state",
        deviceCode: "codex-state",
        userCode: "ABCD-1234",
        verificationUri: "https://auth.openai.com/codex/device",
        verificationUriComplete: "https://auth.openai.com/codex/device",
        expiresAt: new Date(Date.now() + 900_000).toISOString(),
        intervalSeconds: 5,
        codexHome: "/tmp/codex-home",
        message: null,
      })),
      completeDeviceAuth: vi.fn(async () => ({
        mode: "READY" as const,
        codexHome: "/tmp/codex-home",
        modelId: "gpt-5-codex",
        accountLabel: "ChatGPT/Codex subscription",
        message: null,
      })),
      deleteCodexHome: vi.fn(async () => undefined),
      enabled: () => true,
    };
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
      codexCli as never,
    );

    const start = await service.startConnection(
      "user-1",
      "openai",
      "SUBSCRIPTION_CLI",
      "codex-subscription",
      "https://germinai.de/provider-connections/openai/callback",
    );

    expect(start.mode).toBe("CLI_DEVICE_CODE");
    expect(start.connectionProfileId).toBe("codex-subscription");
    expect((start as { userCode?: string }).userCode).toBe("ABCD-1234");
    expect(start).not.toHaveProperty("codexHome");

    const completed = await service.completeDeviceConnection("user-1", "openai", {
      deviceCode: "codex-state",
      connectionProfileId: "codex-subscription",
    });

    expect(completed.mode).toBe("READY");
    expect(completed.connection?.authMethod).toBe("SUBSCRIPTION_CLI");
    const resolved = await service.resolveReadyConnection("user-1", "openai");
    expect(resolved?.connectionProfileId).toBe("codex-subscription");
    expect(resolved?.codexHome).toBe("/tmp/codex-home");
    expect(resolved?.modelId).toBe("gpt-5-codex");
  });

  it("reads migrated provider connection secrets that decrypt to a raw legacy key", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows = [
      providerConnectionRow({
        connection_profile_id: "openrouter-oauth",
        encrypted_secret_json: crypto.encrypt("legacy-key"),
      }),
    ];
    const service = new ProviderConnectionService(
      fakeRepository(rows, crypto) as never,
      new ProviderCatalogService(),
      crypto,
    );

    await expect(service.getApiKey("user-1", "openrouter")).resolves.toBe("legacy-key");
  });
});

function fakeRepository(rows: ProviderConnectionRow[], crypto: CryptoService) {
  return {
    listByUser: async (userId: string) => rows.filter((row) => row.user_id === userId),
    findByIdAndUser: async (connectionId: number, userId: string) =>
      rows.find((row) => row.id === connectionId && row.user_id === userId),
    findReady: async (
      userId: string,
      providerId: string,
      options: { connectionId?: number; authMethods?: string[] } = {},
    ) =>
      rows.find(
        (row) =>
          row.user_id === userId &&
          row.provider_id === providerId &&
          row.status === "READY" &&
          (options.connectionId == null || row.id === options.connectionId) &&
          (!options.authMethods?.length || options.authMethods.includes(row.auth_method)),
      ),
    hasReady: async (userId: string, providerId: string) =>
      rows.some(
        (row) => row.user_id === userId && row.provider_id === providerId && row.status === "READY",
      ),
    upsert: async (input: {
      userId: string;
      providerId: string;
      connectionProfileId: string;
      authMethod: string;
      status: string;
      displayName: string | null;
      encryptedSecretJson: string | null;
      keyVersion: number;
      defaultModelId?: string | null;
      tokenExpiresAt?: Date | null;
      scopes?: string | null;
      providerAccountId?: string | null;
      providerAccountLabel?: string | null;
    }) => {
      const existing = rows.find(
        (row) =>
          row.user_id === input.userId &&
          row.provider_id === input.providerId &&
          row.connection_profile_id === input.connectionProfileId,
      );
      const row = providerConnectionRow({
        id: existing?.id ?? rows.length + 1,
        user_id: input.userId,
        provider_id: input.providerId,
        connection_profile_id: input.connectionProfileId,
        auth_method: input.authMethod,
        status: input.status,
        display_name: input.displayName,
        encrypted_secret_json:
          input.encryptedSecretJson ?? crypto.encrypt(JSON.stringify({ apiKey: "test" })),
        key_version: input.keyVersion,
        provider_account_id: input.providerAccountId ?? null,
        provider_account_label: input.providerAccountLabel ?? input.defaultModelId ?? null,
        token_expires_at: input.tokenExpiresAt ?? null,
        scopes: input.scopes ?? null,
      });
      if (existing) {
        Object.assign(existing, {
          ...row,
          id: existing.id,
          created_at: existing.created_at,
        });
        return existing;
      }
      rows.push(row);
      return row;
    },
    updateSecret: async (input: {
      connectionId: number;
      encryptedSecretJson: string;
      keyVersion: number;
      tokenExpiresAt?: Date | null;
      scopes?: string | null;
    }) => {
      const row = rows.find((item) => item.id === input.connectionId);
      if (!row) return undefined;
      row.encrypted_secret_json = input.encryptedSecretJson;
      row.key_version = input.keyVersion;
      row.token_expires_at = input.tokenExpiresAt ?? null;
      row.scopes = input.scopes ?? null;
      row.updated_at = new Date();
      return row;
    },
    deleteApiKeyMethods: async (userId: string, providerId: string) => {
      for (let index = rows.length - 1; index >= 0; index -= 1) {
        const row = rows[index];
        if (
          row?.user_id === userId &&
          row.provider_id === providerId &&
          ["API_KEY", "OAUTH_PKCE", "OAUTH_AUTH_CODE"].includes(row.auth_method)
        ) {
          rows.splice(index, 1);
        }
      }
    },
    deleteConnection: async (userId: string, connectionId: number) => {
      const index = rows.findIndex((row) => row.id === connectionId && row.user_id === userId);
      if (index >= 0) rows.splice(index, 1);
    },
    deleteByUser: async (userId: string) => {
      for (let index = rows.length - 1; index >= 0; index -= 1) {
        if (rows[index]?.user_id === userId) rows.splice(index, 1);
      }
    },
  };
}

function providerConnectionRow(
  overrides: Partial<ProviderConnectionRow> = {},
): ProviderConnectionRow {
  const now = new Date();
  return {
    id: 1,
    user_id: "user-1",
    provider_id: "openrouter",
    connection_profile_id: "openrouter-oauth",
    auth_method: "OAUTH_PKCE",
    status: "READY",
    display_name: "OpenRouter",
    provider_account_id: null,
    provider_account_label: null,
    scopes: null,
    encrypted_secret_json: null,
    token_expires_at: null,
    key_version: 1,
    default_connection: true,
    last_validated_at: now,
    created_at: now,
    updated_at: now,
    ...overrides,
  };
}
