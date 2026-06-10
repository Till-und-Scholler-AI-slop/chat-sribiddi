import { HttpError } from "../errors/http-error.js";

export class StreamConcurrencyGate {
  private readonly active = new Map<string, number>();
  private readonly acquiredAt = new Map<string, number>();
  private static readonly DEFAULT_ENTRY_TTL_MS = 10 * 60_000;
  private lastPrunedAt = Date.now();

  acquire(userId: string, limit: number) {
    if (limit <= 0) return;
    this.pruneStaleEntries();
    const current = this.active.get(userId) ?? 0;
    if (current >= limit) {
      throw new HttpError(429, "CONFLICT", "Parallel stream limit exceeded");
    }
    this.active.set(userId, current + 1);
    this.acquiredAt.set(userId, Date.now());
  }

  release(userId: string) {
    const current = this.active.get(userId) ?? 0;
    if (current <= 1) {
      this.active.delete(userId);
      this.acquiredAt.delete(userId);
    } else {
      this.active.set(userId, current - 1);
    }
  }

  /**
   * The map can leak entries if a `release` is lost to a process crash or an
   * unhandled upstream error. Sweep any user that has been "active" for an
   * absurdly long time so the gate cannot grow unbounded across hours of
   * server uptime.
   */
  private pruneStaleEntries(now = Date.now()) {
    if (now - this.lastPrunedAt < 60_000) return;
    this.lastPrunedAt = now;
    const cutoff = now - StreamConcurrencyGate.DEFAULT_ENTRY_TTL_MS;
    for (const [userId, acquiredAt] of this.acquiredAt) {
      if (acquiredAt < cutoff) {
        this.active.delete(userId);
        this.acquiredAt.delete(userId);
      }
    }
  }
}
