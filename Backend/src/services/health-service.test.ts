import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { env } from "../config/env.js";
import type { Db } from "../db/database.js";
import { HealthService } from "./health-service.js";
import type { RedisService } from "./redis-service.js";

const originalGatewaySecret = env.PROVIDER_GATEWAY_SHARED_SECRET;

beforeEach(() => {
  env.PROVIDER_GATEWAY_SHARED_SECRET = "health-test-shared-secret";
});

afterEach(() => {
  env.PROVIDER_GATEWAY_SHARED_SECRET = originalGatewaySecret;
  vi.unstubAllGlobals();
});

describe("HealthService", () => {
  it("reports Redis failure as DEGRADED while keeping BYOK ready", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ status: "ready" })),
    );
    const health = new HealthService(database(true), redis(false));

    const result = await health.readiness();

    expect(result.httpStatus).toBe(200);
    expect(result.body).toMatchObject({
      status: "DEGRADED",
      components: { database: "UP", redis: "DOWN", providerGateway: "UP" },
      capabilities: { guestChat: false, byokChat: true },
    });
  });

  it("fails readiness when PostgreSQL is unavailable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ status: "ready" })),
    );
    const health = new HealthService(database(false), redis(true));

    const result = await health.readiness();

    expect(result.httpStatus).toBe(503);
    expect(result.body.status).toBe("DOWN");
    expect(result.body.components.database).toBe("DOWN");
  });

  it("lowers readiness before shutdown draining begins", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const health = new HealthService(database(true), redis(true));

    health.beginShutdown();
    const result = await health.readiness();

    expect(result).toMatchObject({
      httpStatus: 503,
      body: { status: "DOWN", components: { process: "DRAINING" } },
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

function database(available: boolean) {
  return {
    selectNoFrom: () => ({
      executeTakeFirstOrThrow: async () => {
        if (!available) throw new Error("database unavailable");
        return { ok: 1 };
      },
    }),
  } as unknown as Db;
}

function redis(available: boolean) {
  return {
    enabled: true,
    ping: async () => available,
  } as unknown as RedisService;
}
