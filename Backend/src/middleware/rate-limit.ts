import type { MiddlewareHandler } from "hono";
import type { AppContext } from "../app-context.js";
import { env } from "../config/env.js";
import { HttpError } from "../errors/http-error.js";
import type { AppVariables } from "./request-context.js";

const buckets = new Map<string, { resetAt: number; count: number }>();
let lastPrunedBucket = minuteBucket();

/** Drops rate-limit counters from previous minute windows to avoid unbounded growth. */
export function pruneExpiredRateLimitBuckets(now = Date.now()) {
  const currentBucket = minuteBucket(now);
  if (currentBucket === lastPrunedBucket) return;
  lastPrunedBucket = currentBucket;
  for (const [key, entry] of buckets) {
    if (entry.resetAt <= now) {
      buckets.delete(key);
    }
  }
}

export const rateLimitMiddleware = (
  appContext: AppContext,
): MiddlewareHandler<{ Variables: AppVariables }> => {
  return async (c, next) => {
    if (!env.RATE_LIMIT_ENABLED || c.req.method === "OPTIONS") {
      await next();
      return;
    }
    pruneExpiredRateLimitBuckets();
    const userId = appContext.services.auth.resolveUserId(c);
    const limit = userId
      ? env.RATE_LIMIT_AUTHENTICATED_PER_MINUTE
      : env.RATE_LIMIT_ANONYMOUS_PER_MINUTE;
    const key = `${userId ? `user:${userId}` : `ip:${clientIp(c.req.raw)}`}:${minuteBucket()}`;
    const entry = buckets.get(key) ?? { resetAt: Date.now() + 60_000, count: 0 };
    entry.count += 1;
    buckets.set(key, entry);
    if (entry.count > limit) {
      throw new HttpError(429, "CONFLICT", "Rate limit exceeded");
    }
    c.header("X-RateLimit-Limit", String(limit));
    c.header("X-RateLimit-Remaining", String(Math.max(0, limit - entry.count)));
    await next();
  };
};

function minuteBucket(now = Date.now()) {
  return Math.floor(now / 60_000);
}

function clientIp(request: Request) {
  return (
    request.headers.get("x-forwarded-for")?.split(",", 1)[0]?.trim() ||
    request.headers.get("x-real-ip")?.trim() ||
    "unknown"
  );
}
