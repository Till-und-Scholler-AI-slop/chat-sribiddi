import { describe, expect, it } from "vitest";
import { GuestQuotaService } from "./guest-quota-service.js";

describe("GuestQuotaService memory counters", () => {
  it("drops counters from previous days", () => {
    const service = new GuestQuotaService();

    service.memory.set("guest-quota:stale", { day: "2000-01-01", count: 3 });
    service.memory.set("guest-quota:current", { day: "2026-06-09", count: 1 });

    service.pruneStaleMemoryCounters("2026-06-09");

    expect(service.memory.has("guest-quota:stale")).toBe(false);
    expect(service.memory.has("guest-quota:current")).toBe(true);
  });
});
