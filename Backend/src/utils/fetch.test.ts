import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchWithTimeout } from "./fetch.js";

const originalFetch = globalThis.fetch;

describe("fetchWithTimeout", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.useRealTimers();
  });

  it("aborts the request when the timeout elapses", async () => {
    let abortSignal: AbortSignal | undefined;
    globalThis.fetch = vi.fn((_input: unknown, init?: RequestInit) => {
      abortSignal = init?.signal ?? undefined;
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError")),
        );
      });
    }) as typeof fetch;

    const promise = fetchWithTimeout("https://example.com", { timeoutMs: 100 });
    const expectation = expect(promise).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(200);
    await expectation;
    expect(abortSignal?.aborted).toBe(true);
  });

  it("returns the upstream response when it finishes before the timeout", async () => {
    globalThis.fetch = vi.fn(async () => new Response("ok", { status: 200 })) as typeof fetch;
    const response = await fetchWithTimeout("https://example.com", { timeoutMs: 5_000 });
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("ok");
  });

  it("propagates the caller-supplied abort signal", async () => {
    globalThis.fetch = vi.fn((_input: unknown, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("aborted")));
      });
    }) as typeof fetch;
    const controller = new AbortController();
    const promise = fetchWithTimeout("https://example.com", { timeoutMs: 30_000, signal: controller.signal });
    queueMicrotask(() => controller.abort());
    await expect(promise).rejects.toThrow();
  });
});
