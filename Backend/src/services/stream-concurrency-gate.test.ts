import { describe, expect, it } from "vitest";
import { StreamConcurrencyGate } from "./stream-concurrency-gate.js";

describe("StreamConcurrencyGate", () => {
  it("permits up to the configured limit and rejects the next", () => {
    const gate = new StreamConcurrencyGate();
    gate.acquire("user-1", 2);
    gate.acquire("user-1", 2);
    expect(() => gate.acquire("user-1", 2)).toThrow(/parallel stream limit/i);
  });

  it("decrements the active count on release", () => {
    const gate = new StreamConcurrencyGate();
    gate.acquire("user-1", 2);
    gate.acquire("user-1", 2);
    gate.release("user-1");
    // Should now have room for one more
    expect(() => gate.acquire("user-1", 2)).not.toThrow();
  });

  it("treats a limit of zero as a no-op", () => {
    const gate = new StreamConcurrencyGate();
    expect(() => gate.acquire("user-1", 0)).not.toThrow();
    expect(() => gate.acquire("user-1", 0)).not.toThrow();
  });

  it("does not affect other users when one user is at the limit", () => {
    const gate = new StreamConcurrencyGate();
    gate.acquire("user-1", 1);
    expect(() => gate.acquire("user-2", 1)).not.toThrow();
  });
});
