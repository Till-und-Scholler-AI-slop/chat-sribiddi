import crypto from "node:crypto";
import type { MiddlewareHandler } from "hono";

export type AppVariables = {
  requestId: string;
};

export const requestContextMiddleware: MiddlewareHandler<{ Variables: AppVariables }> = async (
  c,
  next,
) => {
  const requestId = c.req.header("X-Request-Id")?.trim() || crypto.randomUUID();
  c.set("requestId", requestId);
  c.header("X-Request-Id", requestId);
  await next();
};
