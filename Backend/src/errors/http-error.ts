import type { ContentfulStatusCode } from "hono/utils/http-status";

export type ApiErrorCode =
  | "VALIDATION_ERROR"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "CONFLICT"
  | "API_KEY_REQUIRED"
  | "MODEL_NOT_ALLOWED"
  | "MANAGED_PROVIDER_UNCONFIGURED"
  | "DAILY_CAP_EXCEEDED"
  | "TOOL_ERROR"
  | "UPSTREAM_ERROR"
  | "INTERNAL_ERROR";

export class HttpError extends Error {
  readonly status: ContentfulStatusCode;
  readonly code: ApiErrorCode;
  readonly details?: unknown;

  constructor(
    status: ContentfulStatusCode,
    code: ApiErrorCode,
    message: string,
    details?: unknown,
  ) {
    super(message);
    this.name = "HttpError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export const badRequest = (message: string, details?: unknown) =>
  new HttpError(400, "VALIDATION_ERROR", message, details);

export const unauthorized = (message = "Authentication required") =>
  new HttpError(401, "UNAUTHORIZED", message);

export const forbidden = (message = "Forbidden") => new HttpError(403, "FORBIDDEN", message);

export const notFound = (message = "Not found") => new HttpError(404, "NOT_FOUND", message);

export const conflict = (message = "Conflict") => new HttpError(409, "CONFLICT", message);

export const upstreamError = (message: string, details?: unknown) =>
  new HttpError(502, "UPSTREAM_ERROR", message, details);
