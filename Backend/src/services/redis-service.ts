import { Redis } from "ioredis";
import { env } from "../config/env.js";
import { logger, safeError } from "../observability/logger.js";
import { redisAvailable } from "../observability/metrics.js";

const FIXED_WINDOW_SCRIPT = `
local current = redis.call('INCRBY', KEYS[1], ARGV[1])
if current == tonumber(ARGV[1]) then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
local ttl = redis.call('PTTL', KEYS[1])
return {current, ttl}
`;

const RESERVE_DAILY_SCRIPT = `
local cap = tonumber(ARGV[1])

for _, key in ipairs(KEYS) do
  local current = tonumber(redis.call('GET', key) or '0')
  if current >= cap then
    return {0}
  end
end

for _, key in ipairs(KEYS) do
  local current = redis.call('INCR', key)
  if current == 1 then
    redis.call('EXPIRE', key, ARGV[2])
  end
end

return {1}
`;

const ROLLBACK_DAILY_SCRIPT = `
local counts = {}

for index, key in ipairs(KEYS) do
  local raw = redis.call('GET', key)
  if raw then
    local current = tonumber(raw)
    if not current then
      return redis.error_reply('Guest quota counter is not numeric')
    end
    counts[index] = current
  else
    counts[index] = 0
  end
end

for index, key in ipairs(KEYS) do
  local current = counts[index]
  if current > 1 then
    redis.call('DECR', key)
  elseif current ~= 0 then
    redis.call('DEL', key)
  end
end

return {1}
`;

export class RedisService {
  private readonly client: Redis | null;
  private connectPromise: Promise<void> | null = null;
  private closing = false;

  constructor(client?: Redis | null) {
    this.client =
      client === undefined
        ? env.RATE_LIMIT_BACKEND === "redis"
          ? new Redis({
              host: env.REDIS_HOST,
              port: env.REDIS_PORT,
              ...defined("password", env.REDIS_PASSWORD?.trim() || undefined),
              lazyConnect: true,
              connectTimeout: 1_500,
              maxRetriesPerRequest: 1,
              retryStrategy: (attempt) => Math.min(250 * 2 ** Math.min(attempt - 1, 4), 5_000),
            })
          : null
        : client;

    this.client?.on("ready", () => redisAvailable.set(1));
    this.client?.on("close", () => redisAvailable.set(0));
    this.client?.on("error", (error) => {
      redisAvailable.set(0);
      logger.warn({ error: safeError(error) }, "Redis connection error");
    });
  }

  get enabled() {
    return this.client !== null;
  }

  async ping() {
    if (!this.client) return true;
    try {
      const client = await this.connectedClient();
      const result = await client.ping();
      const available = result === "PONG";
      redisAvailable.set(available ? 1 : 0);
      return available;
    } catch (error) {
      redisAvailable.set(0);
      logger.warn({ error: safeError(error) }, "Redis readiness probe failed");
      return false;
    }
  }

  async consumeFixedWindow(key: string, cost: number, windowMs: number) {
    const client = await this.connectedClient();
    const result = (await client.eval(
      FIXED_WINDOW_SCRIPT,
      1,
      key,
      String(cost),
      String(windowMs),
    )) as [number, number];
    return { count: Number(result[0]), ttlMs: Math.max(0, Number(result[1])) };
  }

  async reserveDaily(keys: readonly string[], cap: number, ttlSeconds: number) {
    if (keys.length === 0) return { accepted: true };
    const client = await this.connectedClient();
    const result = (await client.eval(
      RESERVE_DAILY_SCRIPT,
      keys.length,
      ...keys,
      String(cap),
      String(ttlSeconds),
    )) as [number];
    return { accepted: Number(result[0]) === 1 };
  }

  async rollbackDaily(keys: readonly string[]) {
    if (keys.length === 0) return;
    const client = await this.connectedClient();
    await client.eval(ROLLBACK_DAILY_SCRIPT, keys.length, ...keys);
  }

  async close() {
    const client = this.client;
    if (!client) return;
    this.closing = true;
    if (client.status === "end") {
      redisAvailable.set(0);
      return;
    }
    try {
      if (client.status === "ready") {
        await client.quit();
      } else {
        client.disconnect(false);
      }
    } catch {
      client.disconnect(false);
    } finally {
      redisAvailable.set(0);
    }
  }

  private async connectedClient() {
    const client = this.client;
    if (!client) throw new Error("Redis backend is disabled");
    if (this.closing) throw new Error("Redis client is closing");
    if (this.connectPromise) {
      await this.connectPromise;
    } else if (client.status === "wait" || client.status === "end") {
      await this.connect(client);
    }
    if (client.status !== "ready") {
      throw new Error(`Redis is not ready (${client.status})`);
    }
    return client;
  }

  private async connect(client: Redis) {
    const attempt = client.connect();
    this.connectPromise = attempt;
    try {
      await attempt;
    } finally {
      if (this.connectPromise === attempt) this.connectPromise = null;
    }
  }
}

function defined<K extends string, V>(key: K, value: V | undefined) {
  return value === undefined ? {} : ({ [key]: value } as Record<K, V>);
}
