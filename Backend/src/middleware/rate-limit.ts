import type { Context, MiddlewareHandler } from "hono";
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
    const resolvedClientIp = clientIp(c);
    c.set("clientIp", resolvedClientIp);
    if (!env.RATE_LIMIT_ENABLED || c.req.method === "OPTIONS") {
      await next();
      return;
    }
    if (isOperationalPath(c.req.path)) {
      await next();
      return;
    }
    pruneExpiredRateLimitBuckets();
    const userId = appContext.services.auth.resolveUserId(c);
    const limit = userId
      ? env.RATE_LIMIT_AUTHENTICATED_PER_MINUTE
      : env.RATE_LIMIT_ANONYMOUS_PER_MINUTE;
    const now = Date.now();
    const resetAt = (minuteBucket(now) + 1) * 60_000;
    const subject = userId ? `user:${userId}` : `ip:${resolvedClientIp}`;
    const key = `rate-limit:${subject}:${minuteBucket(now)}`;
    const cost = requestWeight(c.req.method, c.req.path);
    let count: number;
    let ttlMs = Math.max(1, resetAt - now);
    try {
      if (!appContext.services.redis.enabled) throw new Error("Redis rate limiter disabled");
      const consumed = await appContext.services.redis.consumeFixedWindow(key, cost, ttlMs);
      count = consumed.count;
      ttlMs = consumed.ttlMs || ttlMs;
    } catch {
      if (!userId && env.RATE_LIMIT_BACKEND === "redis") {
        throw new HttpError(
          503,
          "SERVICE_UNAVAILABLE",
          "Anonymous access is temporarily unavailable. Please retry later.",
        );
      }
      count = consumeMemoryBucket(key, cost, resetAt);
    }
    const retryAfter = Math.max(1, Math.ceil(ttlMs / 1_000));
    c.header("X-RateLimit-Reset", String(Math.ceil((now + ttlMs) / 1_000)));
    if (count > limit) {
      c.header("Retry-After", String(retryAfter));
      throw new HttpError(429, "RATE_LIMITED", "Rate limit exceeded");
    }
    c.header("X-RateLimit-Limit", String(limit));
    c.header("X-RateLimit-Remaining", String(Math.max(0, limit - count)));
    await next();
  };
};

function minuteBucket(now = Date.now()) {
  return Math.floor(now / 60_000);
}

function consumeMemoryBucket(key: string, cost: number, resetAt: number) {
  const entry = buckets.get(key) ?? { resetAt, count: 0 };
  entry.count += cost;
  buckets.set(key, entry);
  return entry.count;
}

function clientIp(c: Context) {
  const remoteAddress = (
    c.env as { incoming?: { socket?: { remoteAddress?: string | undefined } } } | undefined
  )?.incoming?.socket?.remoteAddress;
  const trusted = remoteAddress ? isTrustedProxy(remoteAddress, env.trustedProxies) : false;
  if (trusted) {
    return (
      c.req.header("x-forwarded-for")?.split(",", 1)[0]?.trim() ||
      c.req.header("x-real-ip")?.trim() ||
      remoteAddress
    );
  }
  return remoteAddress || "unknown";
}

export function isTrustedProxy(remoteAddress: string, trustedProxies: string[]) {
  const address = normalizeIp(remoteAddress);
  return trustedProxies.some((entry) => {
    const candidate = entry.trim();
    if (!candidate) return false;
    if (!candidate.includes("/")) return normalizeIp(candidate) === address;
    const [network, rawPrefix] = candidate.split("/", 2);
    const prefix = Number(rawPrefix);
    const ip = ipv4Number(address);
    const subnet = ipv4Number(normalizeIp(network ?? ""));
    if (ip === null || subnet === null || !Number.isInteger(prefix) || prefix < 0 || prefix > 32) {
      return false;
    }
    const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0;
    return (ip & mask) >>> 0 === (subnet & mask) >>> 0;
  });
}

function normalizeIp(value: string) {
  const trimmed = value.trim().toLowerCase();
  return trimmed.startsWith("::ffff:") ? trimmed.slice("::ffff:".length) : trimmed;
}

function ipv4Number(value: string) {
  const parts = value.split(".");
  if (parts.length !== 4) return null;
  const octets = parts.map(Number);
  if (octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return null;
  return (
    (((octets[0] ?? 0) << 24) |
      ((octets[1] ?? 0) << 16) |
      ((octets[2] ?? 0) << 8) |
      (octets[3] ?? 0)) >>>
    0
  );
}

function requestWeight(method: string, path: string) {
  if (method === "POST" && path.includes("/messages") && path.endsWith("/stream")) return 10;
  if (method === "POST" && path === "/anonymous/chats/stream") return 10;
  if (method === "POST" && path.includes("/documents")) return 5;
  return 1;
}

function isOperationalPath(path: string) {
  return (
    path === "/health" ||
    path === "/health/live" ||
    path === "/health/ready" ||
    path === "/actuator/health" ||
    path === "/internal/metrics"
  );
}
