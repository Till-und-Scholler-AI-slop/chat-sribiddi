import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProviderConnectionRow } from "../db/types.js";
import { CryptoService } from "./crypto-service.js";
import { ProviderCatalogService } from "./provider-catalog-service.js";
import { ProviderConnectionService } from "./provider-connection-service.js";

const secret = Buffer.alloc(32, 9).toString("base64");

describe("ProviderConnectionService", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
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

    const start = service.startConnection(
      "user-1",
      "openrouter",
      "OAUTH_PKCE",
      "https://germinai.de/provider-connections/openrouter/callback",
    );

    expect(start.mode).toBe("REDIRECT");
    expect(start.authorizationUrl).toContain("https://openrouter.ai/auth");
    expect(start.authorizationUrl).toContain("code_challenge=");

    const completed = await service.completeOpenRouterPkce("user-1", start.state, "code-123");

    expect(completed.providerId).toBe("openrouter");
    expect(completed.authMethod).toBe("OAUTH_PKCE");
    expect(await service.getApiKey("user-1", "openrouter")).toBe("sk-openrouter-user");
  });

  it("reads migrated provider connection secrets that decrypt to a raw legacy key", async () => {
    const crypto = new CryptoService(secret, 1);
    const rows = [
      providerConnectionRow({
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
    upsert: async (input: {
      userId: string;
      providerId: string;
      authMethod: string;
      status: string;
      displayName: string | null;
      encryptedSecretJson: string | null;
      keyVersion: number;
      defaultModelId?: string | null;
      tokenExpiresAt?: Date | null;
      scopes?: string | null;
    }) => {
      const row = providerConnectionRow({
        id: rows.length + 1,
        user_id: input.userId,
        provider_id: input.providerId,
        auth_method: input.authMethod,
        status: input.status,
        display_name: input.displayName,
        encrypted_secret_json:
          input.encryptedSecretJson ?? crypto.encrypt(JSON.stringify({ apiKey: "test" })),
        key_version: input.keyVersion,
        provider_account_label: input.defaultModelId ?? null,
        token_expires_at: input.tokenExpiresAt ?? null,
        scopes: input.scopes ?? null,
      });
      rows.push(row);
      return row;
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
