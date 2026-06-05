import type { MiddlewareHandler } from "hono";
import type { AppContext } from "../app-context.js";
import { env } from "../config/env.js";
import { HttpError } from "../errors/http-error.js";
import type { AppVariables } from "./request-context.js";

const buckets = new Map<string, { resetAt: number; count: number }>();

export const rateLimitMiddleware = (
  appContext: AppContext,
): MiddlewareHandler<{ Variables: AppVariables }> => {
  return async (c, next) => {
    if (!env.RATE_LIMIT_ENABLED || c.req.method === "OPTIONS") {
      await next();
      return;
    }
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

function minuteBucket() {
  return Math.floor(Date.now() / 60_000);
}

function clientIp(request: Request) {
  return (
    request.headers.get("x-forwarded-for")?.split(",", 1)[0]?.trim() ||
    request.headers.get("x-real-ip")?.trim() ||
    "unknown"
  );
}
