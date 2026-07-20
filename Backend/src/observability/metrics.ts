import type { Pool } from "pg";
import { Counter, collectDefaultMetrics, Gauge, Histogram, Registry } from "prom-client";

export const metricsRegistry = new Registry();

collectDefaultMetrics({
  register: metricsRegistry,
  prefix: "chat_sribiddi_",
  eventLoopMonitoringPrecision: 10,
});

export const httpRequestsTotal = new Counter({
  name: "chat_http_requests_total",
  help: "HTTP requests handled by the backend",
  labelNames: ["method", "route", "status"] as const,
  registers: [metricsRegistry],
});

export const httpRequestDuration = new Histogram({
  name: "chat_http_request_duration_seconds",
  help: "Backend HTTP request duration",
  labelNames: ["method", "route", "status"] as const,
  buckets: [0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 15, 60],
  registers: [metricsRegistry],
});

export const activeStreams = new Gauge({
  name: "chat_active_streams",
  help: "Currently active chat streams",
  registers: [metricsRegistry],
});

export const streamDuration = new Histogram({
  name: "chat_stream_duration_seconds",
  help: "Chat stream duration",
  labelNames: ["provider", "status"] as const,
  buckets: [0.1, 0.5, 1, 2.5, 5, 15, 30, 60, 120, 300, 900, 1800],
  registers: [metricsRegistry],
});

export const providerRequestsTotal = new Counter({
  name: "chat_provider_requests_total",
  help: "Provider requests by provider and outcome",
  labelNames: ["provider", "status"] as const,
  registers: [metricsRegistry],
});

export const providerTokensTotal = new Counter({
  name: "chat_tokens_total",
  help: "Tokens reported by providers",
  labelNames: ["provider", "type"] as const,
  registers: [metricsRegistry],
});

export const streamTimeToFirstToken = new Histogram({
  name: "chat_stream_ttft_seconds",
  help: "Time from gateway request start to the first provider content event",
  labelNames: ["provider"] as const,
  buckets: [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 45],
  registers: [metricsRegistry],
});

export const toolStepsTotal = new Counter({
  name: "chat_tool_steps_total",
  help: "Backend-orchestrated tool steps by tool and outcome",
  labelNames: ["tool", "status"] as const,
  registers: [metricsRegistry],
});

export const toolStepDuration = new Histogram({
  name: "chat_tool_step_duration_seconds",
  help: "Backend tool execution duration",
  labelNames: ["tool", "status"] as const,
  buckets: [0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30],
  registers: [metricsRegistry],
});

export const documentJobs = new Gauge({
  name: "chat_document_jobs",
  help: "Document jobs currently active in this backend process",
  labelNames: ["status"] as const,
  registers: [metricsRegistry],
});

export const documentJobAttempts = new Counter({
  name: "chat_document_job_attempts_total",
  help: "Document processing attempts by outcome",
  labelNames: ["outcome"] as const,
  registers: [metricsRegistry],
});

export const redisAvailable = new Gauge({
  name: "chat_redis_available",
  help: "Whether Redis is currently reachable",
  registers: [metricsRegistry],
});

let registeredPool: Pool | null = null;

const _dbPoolTotal = new Gauge({
  name: "chat_db_pool_connections",
  help: "PostgreSQL pool connections by state",
  labelNames: ["state"] as const,
  registers: [metricsRegistry],
  collect() {
    const pool = registeredPool;
    if (!pool) return;
    this.set({ state: "total" }, pool.totalCount);
    this.set({ state: "idle" }, pool.idleCount);
    this.set({ state: "waiting" }, pool.waitingCount);
  },
});

export function registerDatabasePoolMetrics(pool: Pool) {
  registeredPool = pool;
}
