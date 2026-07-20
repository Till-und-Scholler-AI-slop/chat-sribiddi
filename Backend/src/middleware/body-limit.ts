import type { MiddlewareHandler } from "hono";
import { bodyLimit } from "hono/body-limit";
import { HttpError } from "../errors/http-error.js";
import type { AppVariables } from "./request-context.js";

const MIB = 1024 * 1024;
const GENERAL_JSON_LIMIT = 1 * MIB;
const AUTHENTICATED_CHAT_LIMIT = 28 * MIB;
const ANONYMOUS_CHAT_LIMIT = 6 * MIB;
const DOCUMENT_UPLOAD_LIMIT = 6 * MIB;

export const requestBodyLimitMiddleware: MiddlewareHandler<{
  Variables: AppVariables;
}> = async (c, next) => {
  if (c.req.method === "GET" || c.req.method === "HEAD" || c.req.method === "OPTIONS") {
    await next();
    return;
  }
  const maxSize = maxRequestBytes(c.req.path);
  const contentLength = Number(c.req.header("content-length") ?? 0);
  if (Number.isFinite(contentLength) && contentLength > maxSize) {
    throw payloadTooLarge(maxSize);
  }
  const middleware = bodyLimit({
    maxSize,
    onError: () => {
      throw payloadTooLarge(maxSize);
    },
  });
  await middleware(c, next);
};

export function maxRequestBytes(path: string) {
  if (path === "/anonymous/chats/stream") return ANONYMOUS_CHAT_LIMIT;
  if (path.includes("/documents")) return DOCUMENT_UPLOAD_LIMIT;
  if (path.includes("/messages")) return AUTHENTICATED_CHAT_LIMIT;
  return GENERAL_JSON_LIMIT;
}

function payloadTooLarge(maxSize: number) {
  return new HttpError(413, "PAYLOAD_TOO_LARGE", "Request body exceeds the configured limit", {
    maxBytes: maxSize,
  });
}
