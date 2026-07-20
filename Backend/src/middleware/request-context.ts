import crypto from "node:crypto";
import type { MiddlewareHandler } from "hono";
import { logger } from "../observability/logger.js";
import { httpRequestDuration, httpRequestsTotal } from "../observability/metrics.js";

export type AppVariables = {
  requestId: string;
  errorCode?: string;
  provider?: string;
  clientIp?: string;
};

export const requestContextMiddleware: MiddlewareHandler<{ Variables: AppVariables }> = async (
  c,
  next,
) => {
  const suppliedRequestId = c.req.header("X-Request-Id")?.trim();
  const requestId =
    suppliedRequestId && /^[A-Za-z0-9._:-]{1,128}$/.test(suppliedRequestId)
      ? suppliedRequestId
      : crypto.randomUUID();
  c.set("requestId", requestId);
  c.header("X-Request-Id", requestId);
  const startedAt = performance.now();
  try {
    await next();
  } finally {
    const durationSeconds = Math.max(0, performance.now() - startedAt) / 1_000;
    const route = routeLabel(c.req.path, (c.req as unknown as { routePath?: string }).routePath);
    const status = String(c.res.status);
    httpRequestsTotal.inc({ method: c.req.method, route, status });
    httpRequestDuration.observe({ method: c.req.method, route, status }, durationSeconds);
    const fields = {
      requestId,
      method: c.req.method,
      route,
      status: c.res.status,
      durationMs: Math.round(durationSeconds * 1_000),
      ...(c.get("provider") ? { provider: c.get("provider") } : {}),
      ...(c.get("errorCode") ? { errorCode: c.get("errorCode") } : {}),
    };
    if (c.req.path.startsWith("/health") || c.req.path === "/actuator/health") {
      logger.debug(fields, "HTTP request completed");
    } else {
      logger.info(fields, "HTTP request completed");
    }
  }
};

function routeLabel(path: string, routePath: string | undefined) {
  const value = routePath?.trim() || path;
  return value.replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/gi, ":id").replace(/\/\d+(?=\/|$)/g, "/:id");
}
