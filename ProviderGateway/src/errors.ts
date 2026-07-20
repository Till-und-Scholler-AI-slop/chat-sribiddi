const ANSI_ESCAPE_PATTERN = new RegExp("\\x1B\\[[0-?]*[ -/]*[@-~]", "g");

export class GatewayError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status = 400,
    readonly retryable = false,
  ) {
    super(message);
    this.name = "GatewayError";
  }
}

export function asGatewayError(error: unknown) {
  if (error instanceof GatewayError) return error;
  if (isAbortError(error)) {
    return new GatewayError(
      "request_aborted",
      "Provider request was cancelled or exceeded its deadline",
      504,
      true,
    );
  }
  const sanitized = error instanceof Error ? sanitizeErrorMessage(error.message) : "";
  return new GatewayError("provider_error", sanitized || "Provider request failed", 502, true);
}

export function sanitizeErrorMessage(message: string) {
  return message
    .replace(ANSI_ESCAPE_PATTERN, "")
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [redacted]")
    .replace(/\b(?:sk|key|token)-[A-Za-z0-9._~+/=-]{8,}\b/gi, "[credential]")
    .replace(/\b[A-Z0-9]{4,}-[A-Z0-9-]{4,}\b/g, "[device-code]")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 4_096);
}

function isAbortError(error: unknown) {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && /abort|deadline|timed out/i.test(`${error.name} ${error.message}`))
  );
}
