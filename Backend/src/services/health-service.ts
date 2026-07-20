import type { Db } from "../db/database.js";
import { env } from "../config/env.js";
import { logger, safeError } from "../observability/logger.js";
import type { RedisService } from "./redis-service.js";

type ComponentState = "UP" | "DOWN" | "DEGRADED" | "DISABLED";

export class HealthService {
  private shuttingDown = false;

  constructor(
    private readonly db: Db,
    private readonly redis: RedisService,
  ) {}

  beginShutdown() {
    this.shuttingDown = true;
  }

  liveness() {
    return { status: "UP" as const };
  }

  async readiness() {
    if (this.shuttingDown) {
      return {
        httpStatus: 503 as const,
        body: {
          status: "DOWN" as const,
          components: { process: "DRAINING" },
        },
      };
    }

    const [database, redis, gateway] = await Promise.all([
      this.databaseState(),
      this.redisState(),
      this.gatewayState(),
    ]);
    const criticalDown = database === "DOWN" || gateway === "DOWN";
    const degraded = redis !== "UP" || gateway === "DEGRADED";
    return {
      httpStatus: criticalDown ? (503 as const) : (200 as const),
      body: {
        status: criticalDown ? ("DOWN" as const) : degraded ? ("DEGRADED" as const) : ("UP" as const),
        components: {
          database,
          redis,
          providerGateway: gateway,
        },
        capabilities: {
          guestChat: redis === "UP",
          byokChat: database === "UP" && gateway !== "DOWN",
        },
      },
    };
  }

  private async databaseState(): Promise<ComponentState> {
    try {
      await this.db.selectNoFrom((eb) => eb.val(1).as("ok")).executeTakeFirstOrThrow();
      return "UP";
    } catch (error) {
      logger.error({ error: safeError(error) }, "PostgreSQL readiness probe failed");
      return "DOWN";
    }
  }

  private async redisState(): Promise<ComponentState> {
    if (!this.redis.enabled) return "DISABLED";
    return (await this.redis.ping()) ? "UP" : "DOWN";
  }

  private async gatewayState(): Promise<ComponentState> {
    if (!env.PROVIDER_GATEWAY_ENABLED) return "DISABLED";
    if (!env.PROVIDER_GATEWAY_SHARED_SECRET?.trim() && !env.isProduction) return "DEGRADED";
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 2_000);
    timer.unref?.();
    try {
      const response = await fetch(`${env.PROVIDER_GATEWAY_BASE_URL.replace(/\/$/, "")}/health/ready`, {
        headers: {
          authorization: `Bearer ${env.PROVIDER_GATEWAY_SHARED_SECRET ?? ""}`,
        },
        signal: controller.signal,
      });
      if (!response.ok) return "DOWN";
      const payload = (await response.json()) as { status?: string };
      return payload.status === "DEGRADED" ? "DEGRADED" : "UP";
    } catch (error) {
      logger.warn({ error: safeError(error) }, "Provider gateway readiness probe failed");
      return env.isProduction ? "DOWN" : "DEGRADED";
    } finally {
      clearTimeout(timer);
    }
  }
}
