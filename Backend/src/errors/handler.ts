import type { Context } from "hono";
import { HTTPException } from "hono/http-exception";
import { ZodError } from "zod";
import { logger, safeError } from "../observability/logger.js";
import { HttpError } from "./http-error.js";

export function handleError(err: Error, c: Context) {
  const requestId = c.get("requestId") as string | undefined;
  if (err instanceof HttpError) {
    c.set("errorCode", err.code);
    return c.json(
      {
        code: err.code,
        message: err.message,
        details: err.details ?? undefined,
        requestId,
      },
      err.status,
    );
  }
  if (err instanceof HTTPException) {
    c.set("errorCode", `HTTP_${err.status}`);
    return err.getResponse();
  }
  if (err instanceof ZodError) {
    c.set("errorCode", "VALIDATION_ERROR");
    return c.json(
      {
        code: "VALIDATION_ERROR",
        message: "Validation failed",
        details: err.flatten(),
        requestId,
      },
      400,
    );
  }

  c.set("errorCode", "INTERNAL_ERROR");
  logger.error({ requestId, error: safeError(err) }, "Unhandled request error");
  return c.json(
    {
      code: "INTERNAL_ERROR",
      message: "Internal server error",
      requestId,
    },
    500,
  );
}
