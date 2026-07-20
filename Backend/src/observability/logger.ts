import pino from "pino";

export const logger = pino({
  level: process.env.LOG_LEVEL?.trim() || "info",
  base: {
    service: "chat-sribiddi-backend",
    environment: process.env.NODE_ENV || "development",
  },
  redact: {
    paths: [
      "apiKey",
      "authorization",
      "cookie",
      "headers.authorization",
      "headers.cookie",
      "headers.x-xsrf-token",
      "credential",
      "credentials",
      "encryptedKey",
      "encryptedSecret",
      "prompt",
      "messages",
      "request.body",
      "response.body",
      "token",
      "accessToken",
      "refreshToken",
    ],
    censor: "[redacted]",
  },
});

export function safeError(error: unknown) {
  if (!(error instanceof Error)) return { type: "unknown" };
  return {
    type: error.name,
    message: sanitizeErrorMessage(error.message),
    ...(process.env.NODE_ENV === "production" || !error.stack
      ? {}
      : { stack: sanitizeErrorMessage(error.stack) }),
  };
}

export function sanitizeErrorMessage(message: string) {
  return message
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [redacted]")
    .replace(/\b(?:sk|key|token)[-_][A-Za-z0-9._-]{8,}\b/gi, "[redacted]")
    .replace(/([?&](?:key|token|access_token|refresh_token)=)[^&\s]+/gi, "$1[redacted]")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 500);
}
