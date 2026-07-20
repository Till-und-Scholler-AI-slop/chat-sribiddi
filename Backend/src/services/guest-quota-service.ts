import crypto from "node:crypto";
import { env } from "../config/env.js";
import { HttpError } from "../errors/http-error.js";
import { RedisService } from "./redis-service.js";

type Counter = { day: string; count: number };

export class GuestQuotaService {
  readonly memory = new Map<string, Counter>();

  constructor(private readonly redis: RedisService = new RedisService()) {}

  async reserve(request: Request, guestId: string | null, trustedClientIp?: string | null) {
    const cap = env.GUEST_OPENROUTER_FREE_DAILY_CAP;
    if (cap <= 0) return new GuestQuotaReservation([]);
    if (env.isProduction && !this.redis.enabled) {
      throw guestQuotaUnavailable();
    }
    const keys = this.keys(request, guestId, trustedClientIp);
    if (this.redis.enabled) {
      try {
        const result = await this.redis.reserveDaily(keys, cap, 36 * 60 * 60);
        if (!result.accepted) throw dailyCapReached(cap);
      } catch (error) {
        if (error instanceof HttpError) throw error;
        throw guestQuotaUnavailable();
      }
    } else {
      this.reserveMemory(keys, cap);
    }

    return new GuestQuotaReservation(keys, (rollbackKeys) => this.rollback(rollbackKeys));
  }

  private reserveMemory(keys: string[], cap: number) {
    const today = day();
    this.pruneStaleMemoryCounters(today);
    for (const key of keys) {
      const current = this.memory.get(key);
      if (current?.day === today && current.count >= cap) throw dailyCapReached(cap);
    }
    for (const key of keys) {
      const current = this.memory.get(key);
      if (!current || current.day !== today) {
        this.memory.set(key, { day: today, count: 1 });
      } else {
        current.count += 1;
      }
    }
  }

  private async rollback(keys: string[]) {
    if (keys.length === 0) return;
    if (this.redis.enabled) {
      try {
        await this.redis.rollbackDaily(keys);
      } catch {
        // best effort
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

  pruneStaleMemoryCounters(today: string) {
    for (const [key, counter] of this.memory) {
      if (counter.day !== today) {
        this.memory.delete(key);
      }
    }
  }

  private keys(_request: Request, guestId: string | null, trustedClientIp?: string | null) {
    const today = day();
    const ip = trustedClientIp?.trim() || "unknown";
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

function guestQuotaUnavailable() {
  return new HttpError(
    503,
    "SERVICE_UNAVAILABLE",
    "Guest quota is temporarily unavailable. Please retry later.",
  );
}

function day() {
  return new Date().toISOString().slice(0, 10);
}

function sha256(value: string) {
  return crypto.createHash("sha256").update(value).digest("hex");
}
