import { describe, expect, it } from "vitest";
import { isTrustedProxy, pruneExpiredRateLimitBuckets } from "./rate-limit.js";

describe("pruneExpiredRateLimitBuckets", () => {
  it("is safe to call repeatedly within the same minute bucket", () => {
    expect(() => pruneExpiredRateLimitBuckets()).not.toThrow();
    expect(() => pruneExpiredRateLimitBuckets()).not.toThrow();
  });
});

describe("isTrustedProxy", () => {
  it("accepts the Docker bridge CIDR and IPv4-mapped addresses", () => {
    const trusted = ["127.0.0.1", "::1", "172.16.0.0/12"];
    expect(isTrustedProxy("172.21.0.1", trusted)).toBe(true);
    expect(isTrustedProxy("::ffff:172.31.255.254", trusted)).toBe(true);
    expect(isTrustedProxy("173.16.0.1", trusted)).toBe(false);
  });

  it("rejects malformed entries and non-matching exact addresses", () => {
    expect(isTrustedProxy("203.0.113.10", ["172.16.0.0/nope", "203.0.113.11"])).toBe(false);
  });
});
