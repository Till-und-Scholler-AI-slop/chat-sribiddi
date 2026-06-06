import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { createApp } from "./app.js";
import { type AppContext, createAppContext } from "./app-context.js";
import { env } from "./config/env.js";
import { createDb } from "./db/database.js";
import { applyFlywaySqlMigrations } from "./test-utils/migrations.js";

describe("Hono backend integration", () => {
  let postgres: StartedPostgreSqlContainer;
  let appContext: AppContext;
  let fetch: (request: Request) => Response | Promise<Response>;

  beforeAll(async () => {
    postgres = await new PostgreSqlContainer("postgres:17-alpine")
      .withDatabase("chat_sribiddi")
      .withUsername("chat")
      .withPassword("chat")
      .start();

    await applyFlywaySqlMigrations(postgres.getConnectionUri());
    const created = createDb(postgres.getConnectionUri());
    appContext = createAppContext({ db: created.db, shutdown: () => created.pool.end() });
    fetch = createApp(appContext).app.fetch;
  }, 120_000);

  afterAll(async () => {
    await appContext?.shutdown();
    await postgres?.stop();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("serves the actuator health endpoint", async () => {
    const response = await fetch(new Request("http://localhost/actuator/health"));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "UP" });
  });

  it("supports dev-header auth, settings, and chat lifecycle", async () => {
    const headers = { "X-Chat-User-Id": "dev-user", "Content-Type": "application/json" };
    const secured = await fetch(new Request("http://localhost/secured", { headers }));
    expect(secured.status).toBe(204);

    const settings = await fetch(
      new Request("http://localhost/settings", {
        method: "PUT",
        headers,
        body: JSON.stringify({ customInstructions: "Use concise answers." }),
      }),
    );
    expect(settings.status).toBe(204);

    const chatResponse = await fetch(
      new Request("http://localhost/chats", {
        method: "POST",
        headers,
        body: JSON.stringify({ title: "Migration smoke" }),
      }),
    );
    expect(chatResponse.status).toBe(201);
    const chat = (await chatResponse.json()) as { chatId: string; title: string };
    expect(chat.title).toBe("Migration smoke");

    const listResponse = await fetch(new Request("http://localhost/chats", { headers }));
    expect(listResponse.status).toBe(200);
    const chats = (await listResponse.json()) as Array<{ chatId: string }>;
    expect(chats.some((item) => item.chatId === chat.chatId)).toBe(true);
  });

  it("serves provider profiles and stores profile-scoped gateway connections", async () => {
    const headers = { "X-Chat-User-Id": "provider-user", "Content-Type": "application/json" };

    const catalogResponse = await fetch(new Request("http://localhost/providers", { headers }));
    expect(catalogResponse.status).toBe(200);
    const catalog = (await catalogResponse.json()) as Array<{
      id: string;
      status: string;
      connectionProfiles: Array<{ id: string; defaultBaseUrl?: string }>;
      connections: Array<{ connectionProfileId: string; defaultModelId?: string }>;
    }>;
    const qwen = catalog.find((provider) => provider.id === "qwen");
    expect(qwen?.connectionProfiles.map((profile) => profile.id)).toEqual([
      "api",
      "coding-plan",
      "portal",
    ]);
    expect(
      qwen?.connectionProfiles.find((profile) => profile.id === "coding-plan")?.defaultBaseUrl,
    ).toBe("https://coding-intl.dashscope.aliyuncs.com/v1");

    const saveResponse = await fetch(
      new Request("http://localhost/provider-connections/qwen/api-key", {
        method: "POST",
        headers,
        body: JSON.stringify({
          connectionProfileId: "coding-plan",
          apiKey: "sk-qwen",
          defaultModelId: "qwen-coder-plus",
        }),
      }),
    );
    expect(saveResponse.status).toBe(200);
    const saved = (await saveResponse.json()) as {
      providerId: string;
      connectionProfileId: string;
      defaultModelId?: string;
    };
    expect(saved).toMatchObject({
      providerId: "qwen",
      connectionProfileId: "coding-plan",
      defaultModelId: "qwen-coder-plus",
    });

    const updatedResponse = await fetch(new Request("http://localhost/providers", { headers }));
    const updated = (await updatedResponse.json()) as typeof catalog;
    const updatedQwen = updated.find((provider) => provider.id === "qwen");
    expect(updatedQwen?.status).toBe("READY");
    expect(updatedQwen?.connections).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          connectionProfileId: "coding-plan",
          defaultModelId: "qwen-coder-plus",
        }),
      ]),
    );
  });

  it("starts OpenRouter OAuth and handles callback success and errors", async () => {
    const headers = { "X-Chat-User-Id": "oauth-user", "Content-Type": "application/json" };

    const startResponse = await fetch(
      new Request("http://localhost/provider-connections/openrouter/connect", {
        method: "POST",
        headers,
        body: JSON.stringify({
          authMethod: "OAUTH_PKCE",
          connectionProfileId: "openrouter-oauth",
        }),
      }),
    );
    expect(startResponse.status).toBe(200);
    const start = (await startResponse.json()) as {
      mode: string;
      state: string;
      authorizationUrl: string;
      connectionProfileId: string;
    };
    expect(start.mode).toBe("REDIRECT");
    expect(start.authorizationUrl).toContain("https://openrouter.ai/auth");
    expect(start.connectionProfileId).toBe("openrouter-oauth");

    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ key: "sk-openrouter-oauth" })),
    );
    const callbackResponse = await fetch(
      new Request(
        `http://localhost/provider-connections/openrouter/callback?state=${encodeURIComponent(start.state)}&code=code-1`,
        { headers },
      ),
    );
    expect(callbackResponse.status).toBe(302);
    expect(callbackResponse.headers.get("location")).toContain("connected=1");

    const errorResponse = await fetch(
      new Request("http://localhost/provider-connections/openrouter/callback?error=access_denied", {
        headers,
      }),
    );
    expect(errorResponse.status).toBe(302);
    expect(errorResponse.headers.get("location")).toContain("connected=0");
  });

  it("starts and polls GitHub Copilot device-code connections", async () => {
    const previousClientId = env.GITHUB_COPILOT_OAUTH_CLIENT_ID;
    const previousScopes = env.GITHUB_COPILOT_OAUTH_SCOPES;
    const headers = { "X-Chat-User-Id": "device-user", "Content-Type": "application/json" };
    env.GITHUB_COPILOT_OAUTH_CLIENT_ID = "github-client";
    env.GITHUB_COPILOT_OAUTH_SCOPES = "read:user copilot";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json({
            device_code: "device-integration",
            user_code: "EFGH-5678",
            verification_uri: "https://github.com/login/device",
            expires_in: 600,
            interval: 5,
          }),
        )
        .mockResolvedValueOnce(
          Response.json({
            error: "authorization_pending",
            error_description: "Waiting for user authorization.",
          }),
        )
        .mockResolvedValueOnce(
          Response.json({
            access_token: "ghu-device",
            token_type: "bearer",
            scope: "read:user copilot",
            expires_in: 3600,
          }),
        ),
    );

    try {
      const startResponse = await fetch(
        new Request("http://localhost/provider-connections/github-copilot/connect", {
          method: "POST",
          headers,
          body: JSON.stringify({
            authMethod: "DEVICE_CODE",
            connectionProfileId: "github-oauth",
          }),
        }),
      );
      expect(startResponse.status).toBe(200);
      const start = (await startResponse.json()) as {
        mode: string;
        deviceCode: string;
        userCode: string;
        connectionProfileId: string;
      };
      expect(start).toMatchObject({
        mode: "DEVICE_CODE",
        deviceCode: "device-integration",
        userCode: "EFGH-5678",
        connectionProfileId: "github-oauth",
      });

      const pendingResponse = await fetch(
        new Request("http://localhost/provider-connections/github-copilot/device-code", {
          method: "POST",
          headers,
          body: JSON.stringify({
            deviceCode: "device-integration",
            connectionProfileId: "github-oauth",
          }),
        }),
      );
      expect(pendingResponse.status).toBe(200);
      expect(await pendingResponse.json()).toMatchObject({ mode: "PENDING" });

      const readyResponse = await fetch(
        new Request("http://localhost/provider-connections/github-copilot/device-code", {
          method: "POST",
          headers,
          body: JSON.stringify({
            deviceCode: "device-integration",
            connectionProfileId: "github-oauth",
          }),
        }),
      );
      expect(readyResponse.status).toBe(200);
      expect(await readyResponse.json()).toMatchObject({
        mode: "READY",
        connectionProfileId: "github-oauth",
      });
    } finally {
      env.GITHUB_COPILOT_OAUTH_CLIENT_ID = previousClientId;
      env.GITHUB_COPILOT_OAUTH_SCOPES = previousScopes;
    }
  });
});
