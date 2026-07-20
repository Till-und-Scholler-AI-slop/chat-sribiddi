import { afterEach, describe, expect, it, vi } from "vitest";
import { env } from "../config/env.js";
import { GuestQuotaService } from "./guest-quota-service.js";
import type { RedisService } from "./redis-service.js";

const originalProduction = env.isProduction;
const originalCap = env.GUEST_OPENROUTER_FREE_DAILY_CAP;

afterEach(() => {
  env.isProduction = originalProduction;
  env.GUEST_OPENROUTER_FREE_DAILY_CAP = originalCap;
});

describe("GuestQuotaService", () => {
  it("drops counters from previous days", () => {
    const service = new GuestQuotaService(redisStub({ enabled: false }));

    service.memory.set("guest-quota:stale", { day: "2000-01-01", count: 3 });
    service.memory.set("guest-quota:current", { day: "2026-06-09", count: 1 });

    service.pruneStaleMemoryCounters("2026-06-09");

    expect(service.memory.has("guest-quota:stale")).toBe(false);
    expect(service.memory.has("guest-quota:current")).toBe(true);
  });

  it("reserves guest quota through Redis in production", async () => {
    env.isProduction = true;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 20;
    const reserveDaily = vi.fn().mockResolvedValue({ accepted: true });
    const service = new GuestQuotaService(redisStub({ enabled: true, reserveDaily }));

    const reservation = await service.reserve(
      new Request("https://germinai.de/api/chat"),
      "guest-1",
      "203.0.113.10",
    );
    reservation.commit();

    expect(reserveDaily).toHaveBeenCalledOnce();
    expect(reserveDaily).toHaveBeenCalledWith(
      expect.arrayContaining([expect.stringMatching(/^guest-quota:/)]),
      20,
      36 * 60 * 60,
    );
    expect(reserveDaily.mock.calls[0]?.[0]).toHaveLength(2);
  });

  it("does not reserve either Redis counter when one cap is exhausted", async () => {
    env.isProduction = true;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 20;
    const rollbackDaily = vi.fn().mockResolvedValue(undefined);
    const service = new GuestQuotaService(
      redisStub({
        enabled: true,
        reserveDaily: vi.fn().mockResolvedValue({ accepted: false }),
        rollbackDaily,
      }),
    );

    await expect(
      service.reserve(new Request("https://germinai.de/api/chat"), "guest-1", "203.0.113.10"),
    ).rejects.toMatchObject({ status: 429, code: "DAILY_CAP_EXCEEDED" });
    expect(rollbackDaily).not.toHaveBeenCalled();
  });

  it("rolls both Redis counters back in one operation", async () => {
    env.isProduction = true;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 20;
    const rollbackDaily = vi.fn().mockResolvedValue(undefined);
    const service = new GuestQuotaService(redisStub({ enabled: true, rollbackDaily }));

    const reservation = await service.reserve(
      new Request("https://germinai.de/api/chat"),
      "guest-1",
      "203.0.113.10",
    );
    await reservation.rollback();
    await reservation.rollback();

    expect(rollbackDaily).toHaveBeenCalledOnce();
    expect(rollbackDaily.mock.calls[0]?.[0]).toHaveLength(2);
  });

  it("fails closed in production when Redis is disabled", async () => {
    env.isProduction = true;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 20;
    const service = new GuestQuotaService(redisStub({ enabled: false }));

    await expect(
      service.reserve(new Request("https://germinai.de/api/chat"), "guest-1", "203.0.113.10"),
    ).rejects.toMatchObject({ status: 503, code: "SERVICE_UNAVAILABLE" });
  });

  it("fails closed in production when Redis is unavailable", async () => {
    env.isProduction = true;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 20;
    const service = new GuestQuotaService(
      redisStub({
        enabled: true,
        reserveDaily: vi.fn().mockRejectedValue(new Error("connection lost")),
      }),
    );

    await expect(
      service.reserve(new Request("https://germinai.de/api/chat"), "guest-1", "203.0.113.10"),
    ).rejects.toMatchObject({ status: 503, code: "SERVICE_UNAVAILABLE" });
  });

  it("does not partially mutate memory counters when one cap is exhausted", async () => {
    env.isProduction = false;
    env.GUEST_OPENROUTER_FREE_DAILY_CAP = 2;
    const service = new GuestQuotaService(redisStub({ enabled: false }));
    const request = new Request("https://germinai.de/api/chat");
    const first = await service.reserve(request, "guest-1", "203.0.113.10");
    first.commit();
    const counters = [...service.memory.values()];
    expect(counters).toHaveLength(2);
    if (!counters[0] || !counters[1]) throw new Error("Expected both guest counters");
    counters[0].count = 2;

    await expect(service.reserve(request, "guest-1", "203.0.113.10")).rejects.toMatchObject({
      status: 429,
      code: "DAILY_CAP_EXCEEDED",
    });
    expect(counters.map((counter) => counter.count)).toEqual([2, 1]);
  });
});

function redisStub(overrides: Partial<RedisService> & { enabled: boolean }) {
  return {
    reserveDaily: vi.fn().mockResolvedValue({ accepted: true }),
    rollbackDaily: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  } as unknown as RedisService;
}
