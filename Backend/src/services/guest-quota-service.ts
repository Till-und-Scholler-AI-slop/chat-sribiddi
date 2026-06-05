import crypto from "node:crypto";
import { Redis } from "ioredis";
import { env } from "../config/env.js";
import { HttpError } from "../errors/http-error.js";

type Counter = { day: string; count: number };

export class GuestQuotaService {
  private readonly memory = new Map<string, Counter>();
  private readonly redis =
    env.RATE_LIMIT_BACKEND === "redis"
      ? new Redis({
          host: env.REDIS_HOST,
          port: env.REDIS_PORT,
          lazyConnect: true,
          maxRetriesPerRequest: 1,
        })
      : null;

  async reserve(request: Request, guestId: string | null) {
    const cap = env.GUEST_OPENROUTER_FREE_DAILY_CAP;
    if (cap <= 0) return new GuestQuotaReservation([]);
    const keys = this.keys(request, guestId);
    const reserved: string[] = [];
    try {
      for (const key of keys) {
        await this.reserveKey(key, cap);
        reserved.push(key);
      }
      return new GuestQuotaReservation(reserved, (rollbackKeys) => this.rollback(rollbackKeys));
    } catch (error) {
      await this.rollback(reserved);
      throw error;
    }
  }

  private async reserveKey(key: string, cap: number) {
    if (this.redis) {
      try {
        if (this.redis.status === "wait") await this.redis.connect();
        const next = await this.redis.incr(key);
        if (next === 1) await this.redis.expire(key, 36 * 60 * 60);
        if (next > cap) {
          await this.redis.decr(key);
          throw dailyCapReached(cap);
        }
        return;
      } catch (error) {
        if (error instanceof HttpError) throw error;
        throw new HttpError(
          503,
          "DAILY_CAP_EXCEEDED",
          "Guest quota is temporarily unavailable. Please retry later.",
        );
      }
    }

    const today = day();
    const current = this.memory.get(key);
    if (!current || current.day !== today) {
      this.memory.set(key, { day: today, count: 1 });
      return;
    }
    if (current.count >= cap) throw dailyCapReached(cap);
    current.count += 1;
  }

  private async rollback(keys: string[]) {
    if (keys.length === 0) return;
    if (this.redis) {
      for (const key of keys) {
        try {
          await this.redis.decr(key);
        } catch {
          // best effort
        }
      }
      return;
    }
    for (const key of keys) {
      const current = this.memory.get(key);
      if (!current) continue;
      if (current.count <= 1) this.memory.delete(key);
      else current.count -= 1;
    }
  }

  private keys(request: Request, guestId: string | null) {
    const today = day();
    const ip = clientIp(request);
    const normalizedGuestId = guestId?.trim() || "anonymous";
    return [
      `guest-quota:${sha256(`${today}:ip:${ip}`)}`,
      `guest-quota:${sha256(`${today}:ip-guest:${ip}:${normalizedGuestId}`)}`,
    ];
  }
}

export class GuestQuotaReservation {
  private closed = false;

  constructor(
    private readonly keys: string[],
    private readonly rollbackFn: (keys: string[]) => Promise<void> = async () => {},
  ) {}

  commit() {
    this.closed = true;
  }

  async rollback() {
    if (this.closed) return;
    this.closed = true;
    await this.rollbackFn(this.keys);
  }
}

function dailyCapReached(cap: number) {
  return new HttpError(429, "DAILY_CAP_EXCEEDED", `Daily guest call cap (${cap}) reached`);
}

function day() {
  return new Date().toISOString().slice(0, 10);
}

function sha256(value: string) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function clientIp(request: Request) {
  const forwardedFor = request.headers.get("x-forwarded-for");
  if (forwardedFor) return forwardedFor.split(",")[0]?.trim() || "unknown";
  return request.headers.get("x-real-ip") || "unknown";
}
