import { describe, expect, it, vi } from "vitest";
import type { UsageRepository } from "../repositories/usage-repository.js";
import type { UserRepository } from "../repositories/user-repository.js";
import { UsageService } from "./usage-service.js";

describe("UsageService dashboard aggregation", () => {
  it("rejects ranges longer than one calendar year before querying the database", async () => {
    const findByProviderId = vi.fn();
    const service = new UsageService(
      { findByProviderId } as unknown as UserRepository,
      {} as UsageRepository,
    );

    await expect(
      service.dashboard(
        "user-1",
        new Date("2025-01-01T00:00:00.000Z"),
        new Date("2026-01-02T00:00:00.000Z"),
      ),
    ).rejects.toThrow("must not exceed one year");
    expect(findByProviderId).not.toHaveBeenCalled();
  });

  it("maps SQL aggregates to the existing dashboard response", async () => {
    const usage = {
      aggregateByModel: vi.fn().mockResolvedValue([
        {
          provider: "OPENAI",
          model_id: "gpt-test",
          calls: 2,
          prompt_tokens: 10,
          completion_tokens: 5,
          total_tokens: 15,
          cost_micro_cents: 7,
        },
      ]),
      aggregateByDay: vi
        .fn()
        .mockResolvedValue([
          { day: "2026-07-13", calls: 2, total_tokens: 15, cost_micro_cents: 7 },
        ]),
    };
    const service = new UsageService(
      { findByProviderId: vi.fn().mockResolvedValue({ id: 42 }) } as unknown as UserRepository,
      usage as unknown as UsageRepository,
    );
    const since = new Date("2026-07-01T00:00:00.000Z");
    const until = new Date("2026-08-01T00:00:00.000Z");

    const result = await service.dashboard("user-1", since, until, "gpt-test");

    expect(usage.aggregateByModel).toHaveBeenCalledWith(42, since, until, "gpt-test");
    expect(usage.aggregateByDay).toHaveBeenCalledWith(42, since, until, "gpt-test");
    expect(result).toMatchObject({
      totalCalls: 2,
      totalTokens: 15,
      totalCostMicroCents: 7,
      items: [
        {
          provider: "OPENAI",
          modelId: "gpt-test",
          calls: 2,
          promptTokens: 10,
          completionTokens: 5,
          totalTokens: 15,
          costMicroCents: 7,
        },
      ],
      daily: [{ day: "2026-07-13", calls: 2, totalTokens: 15, costMicroCents: 7 }],
    });
  });
});
