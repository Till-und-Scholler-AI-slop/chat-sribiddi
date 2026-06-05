import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createApp } from "./app.js";
import { type AppContext, createAppContext } from "./app-context.js";
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
});
