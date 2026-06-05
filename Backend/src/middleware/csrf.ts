import type { MiddlewareHandler } from "hono";
import type { AppContext } from "../app-context.js";
import type { AppVariables } from "./request-context.js";

export const csrfMiddleware = (
  appContext: AppContext,
): MiddlewareHandler<{ Variables: AppVariables }> => {
  return async (c, next) => {
    if (c.req.method !== "OPTIONS") {
      appContext.services.auth.validateCsrf(c);
    }
    await next();
  };
};
