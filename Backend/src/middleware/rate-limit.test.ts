import { describe, expect, it } from "vitest";
import { pruneExpiredRateLimitBuckets } from "./rate-limit.js";

describe("pruneExpiredRateLimitBuckets", () => {
  it("is safe to call repeatedly within the same minute bucket", () => {
    expect(() => pruneExpiredRateLimitBuckets()).not.toThrow();
    expect(() => pruneExpiredRateLimitBuckets()).not.toThrow();
  });
});
