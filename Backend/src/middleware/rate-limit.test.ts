import { Hono } from "hono";
import { afterEach, describe, expect, it } from "vitest";
import type { AppContext } from "../app-context.js";
import { env } from "../config/env.js";
import { isTrustedProxy, pruneExpiredRateLimitBuckets, rateLimitMiddleware } from "./rate-limit.js";
import type { AppVariables } from "./request-context.js";

const originalRateLimitEnabled = env.RATE_LIMIT_ENABLED;

afterEach(() => {
  env.RATE_LIMIT_ENABLED = originalRateLimitEnabled;
});

describe("rateLimitMiddleware", () => {
  it("handles requests without a Node server environment", async () => {
    env.RATE_LIMIT_ENABLED = false;
    const app = new Hono<{ Variables: AppVariables }>();
    app.use("*", rateLimitMiddleware({} as AppContext));
    app.get("/", (c) => c.text(c.get("clientIp") ?? "missing"));

    const response = await app.request("/");

    expect(response.status).toBe(200);
    expect(await response.text()).toBe("unknown");
  });
});

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
