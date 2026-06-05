import { HttpError } from "../errors/http-error.js";

export class StreamConcurrencyGate {
  private readonly active = new Map<string, number>();

  acquire(userId: string, limit: number) {
    if (limit <= 0) return;
    const current = this.active.get(userId) ?? 0;
    if (current >= limit) {
      throw new HttpError(429, "CONFLICT", "Parallel stream limit exceeded");
    }
    this.active.set(userId, current + 1);
  }

  release(userId: string) {
    const current = this.active.get(userId) ?? 0;
    if (current <= 1) this.active.delete(userId);
    else this.active.set(userId, current - 1);
  }
}
