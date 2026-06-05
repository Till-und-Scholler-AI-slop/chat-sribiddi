import type { Context } from "hono";
import { HTTPException } from "hono/http-exception";
import { ZodError } from "zod";
import { HttpError } from "./http-error.js";

export function handleError(err: Error, c: Context) {
  const requestId = c.get("requestId") as string | undefined;
  if (err instanceof HttpError) {
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
    return err.getResponse();
  }
  if (err instanceof ZodError) {
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

  console.error(err);
  return c.json(
    {
      code: "INTERNAL_ERROR",
      message: "Internal server error",
      requestId,
    },
    500,
  );
}
