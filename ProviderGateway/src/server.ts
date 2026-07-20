import { createHash, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import {
  PROVIDER_CONTRACT_VERSION,
  PROVIDER_MODEL_CATALOG_PATH,
  PROVIDER_STREAM_PATH,
  type ProviderStreamRequest,
  ProviderStreamRequestSchema,
} from "@chat-sribiddi/provider-contract";
import { ZodError } from "zod";
import { type GatewayConfigOverrides, loadGatewayConfig } from "./config.js";
import { asGatewayError, GatewayError } from "./errors.js";
import { GatewayMetrics } from "./metrics.js";
import { ProviderModelCatalog } from "./model-catalog.js";
import {
  executeProvider,
  ProcessRegistry,
  type ProviderSink,
  parseCodexExecJsonl,
  validateOpenAiCompatibleBaseUrl,
} from "./providers.js";
import { ProviderSseWriter } from "./sse.js";

export { parseCodexExecJsonl, validateOpenAiCompatibleBaseUrl };

type ProviderExecutor = typeof executeProvider;

type RequestLogContext = {
  requestId?: string;
  provider?: string;
  errorCode?: string;
  providerStreamStarted?: boolean;
};

export type ProviderGatewayOptions = {
  config?: GatewayConfigOverrides;
  executor?: ProviderExecutor;
  metrics?: GatewayMetrics;
};

export class ProviderGatewayRuntime {
  readonly config;
  readonly metrics;
  readonly processes = new ProcessRegistry();
  readonly modelCatalog;
  private readonly controllers = new Set<AbortController>();
  private acceptingRequests = true;
  private shutdownPromise: Promise<void> | null = null;

  constructor(configOverrides: GatewayConfigOverrides = {}, metrics = new GatewayMetrics()) {
    this.config = loadGatewayConfig(configOverrides);
    this.metrics = metrics;
    this.modelCatalog = new ProviderModelCatalog(this.config);
  }

  isReady() {
    return this.acceptingRequests;
  }

  beginDrain() {
    this.acceptingRequests = false;
  }

  register(controller: AbortController) {
    this.controllers.add(controller);
    return () => this.controllers.delete(controller);
  }

  shutdown(server: Server) {
    this.shutdownPromise ??= this.performShutdown(server);
    return this.shutdownPromise;
  }

  private async performShutdown(server: Server) {
    this.beginDrain();
    let closeError: Error | null = null;
    const closed = new Promise<void>((resolve) => {
      server.close((error) => {
        closeError = error ?? null;
        resolve();
      });
    });
    server.closeIdleConnections?.();
    const drained = await Promise.race([
      closed.then(() => true),
      delay(this.config.shutdownGraceMs).then(() => false),
    ]);
    if (!drained) {
      for (const controller of this.controllers) {
        controller.abort(
          new GatewayError("gateway_shutdown", "Provider gateway is shutting down", 503, true),
        );
      }
      this.processes.terminateAll(this.config.cliKillGraceMs);
      server.closeAllConnections?.();
      await Promise.race([closed, delay(this.config.cliKillGraceMs + 1_000)]);
    }
    if (closeError) throw closeError;
  }
}

export function createProviderGateway(options: ProviderGatewayOptions = {}) {
  const runtime = new ProviderGatewayRuntime(options.config, options.metrics);
  const executor = options.executor ?? executeProvider;
  const server = createServer((request, response) => {
    void handleRequest(request, response, runtime, executor);
  });
  server.requestTimeout = runtime.config.bodyReadTimeoutMs;
  server.headersTimeout = Math.min(30_000, runtime.config.bodyReadTimeoutMs);
  server.keepAliveTimeout = 5_000;
  server.maxRequestsPerSocket = 1_000;
  server.on("clientError", (_error, socket) => {
    if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
  });
  return { server, runtime };
}

export function createProviderGatewayServer(options: ProviderGatewayOptions = {}) {
  return createProviderGateway(options).server;
}

async function handleRequest(
  request: IncomingMessage,
  response: ServerResponse,
  runtime: ProviderGatewayRuntime,
  executor: ProviderExecutor,
) {
  const method = request.method ?? "GET";
  const path = requestPath(request);
  const route = metricRoute(path);
  const startedAt = performance.now();
  const logContext: RequestLogContext = {
    requestId: safeRequestId(request.headers["x-request-id"]),
  };
  response.once("finish", () => {
    runtime.metrics.observeRequest(method, route, response.statusCode);
    if (!logContext.providerStreamStarted) {
      logRequest(method, route, response.statusCode, startedAt, logContext);
    }
  });
  try {
    if (method === "GET" && path === "/health/live") {
      sendJson(response, 200, {
        status: "up",
        service: "provider-gateway",
        contractVersion: PROVIDER_CONTRACT_VERSION,
      });
      return;
    }
    if (method === "GET" && (path === "/health/ready" || path === "/health")) {
      const ready = runtime.isReady();
      sendJson(response, ready ? 200 : 503, {
        status: ready ? "ready" : "draining",
        service: "provider-gateway",
        contractVersion: PROVIDER_CONTRACT_VERSION,
        checks: { acceptingRequests: ready },
      });
      return;
    }
    if (method === "GET" && path === "/metrics") {
      requireSharedSecret(request, runtime.config.sharedSecret);
      response.writeHead(200, {
        "content-type": "text/plain; version=0.0.4; charset=utf-8",
        "cache-control": "no-store",
      });
      response.end(runtime.metrics.render(runtime.isReady()));
      return;
    }
    if (method === "GET" && path === PROVIDER_MODEL_CATALOG_PATH) {
      requireSharedSecret(request, runtime.config.sharedSecret);
      const provider = requestUrl(request).searchParams.get("provider") ?? "";
      sendJson(response, 200, await runtime.modelCatalog.list(provider));
      return;
    }
    if (method === "POST" && (path === PROVIDER_STREAM_PATH || path === "/v1/chat/stream")) {
      requireSharedSecret(request, runtime.config.sharedSecret);
      if (!runtime.isReady())
        throw new GatewayError("gateway_draining", "Provider gateway is draining", 503, true);
      const body = await readJsonLimited(request, runtime.config.bodyLimitBytes);
      const parsed = ProviderStreamRequestSchema.parse(body);
      logContext.requestId = parsed.requestId;
      logContext.provider = parsed.provider.kind;
      logContext.providerStreamStarted = true;
      await handleProviderStream(
        request,
        response,
        parsed,
        runtime,
        executor,
        startedAt,
        logContext,
      );
      return;
    }
    sendJson(response, 404, errorPayload("not_found", "Not found"));
  } catch (error) {
    logContext.errorCode = gatewayErrorCode(error);
    sendHttpError(response, error);
  }
}

async function handleProviderStream(
  request: IncomingMessage,
  response: ServerResponse,
  providerRequest: ProviderStreamRequest,
  runtime: ProviderGatewayRuntime,
  executor: ProviderExecutor,
  startedAt: number,
  logContext: RequestLogContext,
) {
  const controller = new AbortController();
  const unregister = runtime.register(controller);
  const cliDeadlineMs =
    providerRequest.provider.kind === "codex"
      ? runtime.config.codexExecTimeoutMs
      : providerRequest.provider.kind === "grok-cli"
        ? runtime.config.grokRpcTimeoutMs
        : runtime.config.maxDeadlineMs;
  const deadlineMs = Math.min(
    providerRequest.timeoutMs ?? runtime.config.maxDeadlineMs,
    runtime.config.maxDeadlineMs,
    cliDeadlineMs,
  );
  const deadline = setTimeout(
    () =>
      controller.abort(
        new GatewayError("provider_timeout", "Provider request exceeded its deadline", 504, true),
      ),
    deadlineMs,
  );
  deadline.unref?.();
  let completed = false;
  const abortForDisconnect = () => {
    if (!completed)
      controller.abort(
        new GatewayError("client_closed", "Provider stream client disconnected", 499),
      );
  };
  request.once("aborted", abortForDisconnect);
  response.once("close", abortForDisconnect);

  const writer = new ProviderSseWriter(
    response,
    providerRequest.requestId,
    runtime.config.heartbeatIntervalMs,
    controller.signal,
  );
  const streamMetric = runtime.metrics.beginStream(providerRequest.provider.kind);
  const sink: ProviderSink = {
    text: (delta) => {
      streamMetric.observeFirstDelta();
      return writer.emit({ type: "text_delta", delta });
    },
    reasoning: (delta) => {
      streamMetric.observeFirstDelta();
      return writer.emit({ type: "reasoning_delta", delta });
    },
    toolCall: (toolCallId, toolName, input) => {
      streamMetric.observeFirstDelta();
      return writer.emit({ type: "tool_call", toolCallId, toolName, input });
    },
    usage: (usage) => writer.emit({ type: "usage", ...usage }),
  };

  try {
    writer.start();
    await writer.emit({
      type: "provider_started",
      provider: providerRequest.provider.kind,
      modelId: providerRequest.modelId,
    });
    const result = await executor(
      providerRequest,
      sink,
      controller.signal,
      runtime.config,
      runtime.processes,
    );
    await writer.emit({ type: "done", finishReason: result.finishReason });
    completed = true;
    streamMetric.finish("success");
  } catch (error) {
    const gatewayError = asGatewayError(
      controller.signal.aborted ? (controller.signal.reason ?? error) : error,
    );
    logContext.errorCode = gatewayError.code;
    runtime.metrics.observeProviderError(providerRequest.provider.kind, gatewayError.code);
    streamMetric.finish(
      gatewayError.code === "client_closed" || gatewayError.code === "request_aborted"
        ? "aborted"
        : "error",
    );
    if (!response.destroyed && !response.writableEnded) {
      await writer
        .emit({
          type: "error",
          code: gatewayError.code,
          message: gatewayError.message,
          retryable: gatewayError.retryable,
        })
        .catch(() => undefined);
    }
  } finally {
    completed = true;
    clearTimeout(deadline);
    request.removeListener("aborted", abortForDisconnect);
    response.removeListener("close", abortForDisconnect);
    unregister();
    await writer.close();
    logRequest("POST", PROVIDER_STREAM_PATH, response.statusCode, startedAt, logContext);
  }
}

export function sendJson(response: ServerResponse, status: number, payload: unknown) {
  if (response.headersSent || response.writableEnded || response.destroyed) return;
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(JSON.stringify(payload));
}

async function readJsonLimited(request: IncomingMessage, limitBytes: number) {
  const declaredLength = Number(request.headers["content-length"] ?? 0);
  if (Number.isFinite(declaredLength) && declaredLength > limitBytes) {
    throw new GatewayError(
      "payload_too_large",
      `Request body exceeds ${limitBytes} bytes`,
      413,
      false,
    );
  }
  const chunks: Buffer[] = [];
  let received = 0;
  for await (const rawChunk of request) {
    const chunk = Buffer.isBuffer(rawChunk) ? rawChunk : Buffer.from(rawChunk);
    received += chunk.byteLength;
    if (received > limitBytes) {
      request.resume();
      throw new GatewayError(
        "payload_too_large",
        `Request body exceeds ${limitBytes} bytes`,
        413,
        false,
      );
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
  } catch {
    throw new GatewayError("invalid_json", "Request body must contain valid JSON", 400, false);
  }
}

function requireSharedSecret(request: IncomingMessage, expected: string) {
  const authorization = request.headers.authorization ?? "";
  const supplied = authorization.startsWith("Bearer ")
    ? authorization.slice("Bearer ".length).trim()
    : "";
  const expectedHash = createHash("sha256").update(expected).digest();
  const suppliedHash = createHash("sha256").update(supplied).digest();
  if (!supplied || !timingSafeEqual(expectedHash, suppliedHash)) {
    throw new GatewayError(
      "unauthorized",
      "Valid ProviderGateway bearer authentication is required",
      401,
      false,
    );
  }
}

function sendHttpError(response: ServerResponse, error: unknown) {
  if (response.headersSent || response.destroyed || response.writableEnded) return;
  if (error instanceof ZodError) {
    sendJson(response, 400, {
      ...errorPayload("invalid_contract", "Provider request does not match contract version 1"),
      details: error.issues.map((issue) => ({
        path: issue.path.join("."),
        message: issue.message,
      })),
    });
    return;
  }
  const gatewayError = asGatewayError(error);
  if (gatewayError.status === 401)
    response.setHeader("www-authenticate", 'Bearer realm="provider-gateway"');
  sendJson(
    response,
    gatewayError.status,
    errorPayload(gatewayError.code, gatewayError.message, gatewayError.retryable),
  );
}

function errorPayload(code: string, message: string, retryable = false) {
  return {
    contractVersion: PROVIDER_CONTRACT_VERSION,
    error: { code, message, retryable },
  };
}

function requestPath(request: IncomingMessage) {
  return requestUrl(request).pathname;
}

function requestUrl(request: IncomingMessage) {
  try {
    return new URL(request.url ?? "/", "http://provider-gateway.internal");
  } catch {
    return new URL("http://provider-gateway.internal/invalid");
  }
}

function metricRoute(path: string) {
  if (path === PROVIDER_STREAM_PATH || path === "/v1/chat/stream") return PROVIDER_STREAM_PATH;
  if (path === "/health" || path === "/health/ready") return "/health/ready";
  if (path === "/health/live" || path === "/metrics") return path;
  if (path === PROVIDER_MODEL_CATALOG_PATH) return path;
  return "unmatched";
}

function gatewayErrorCode(error: unknown) {
  return error instanceof ZodError ? "invalid_contract" : asGatewayError(error).code;
}

function safeRequestId(value: string | string[] | undefined) {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && /^[A-Za-z0-9._:-]{1,128}$/.test(candidate) ? candidate : undefined;
}

function logRequest(
  method: string,
  route: string,
  status: number,
  startedAt: number,
  context: RequestLogContext,
) {
  console.info(
    JSON.stringify({
      level: context.errorCode ? "error" : "info",
      event: "provider_gateway_request_completed",
      method,
      route,
      status,
      durationMs: Math.round(Math.max(0, performance.now() - startedAt)),
      ...(context.requestId ? { requestId: context.requestId } : {}),
      ...(context.provider ? { provider: context.provider } : {}),
      ...(context.errorCode ? { errorCode: context.errorCode } : {}),
    }),
  );
}

function delay(ms: number) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    timer.unref?.();
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { server, runtime } = createProviderGateway();
  server.listen(runtime.config.port, runtime.config.host, () => {
    console.info(
      JSON.stringify({
        level: "info",
        event: "provider_gateway_started",
        host: runtime.config.host,
        port: runtime.config.port,
        contractVersion: PROVIDER_CONTRACT_VERSION,
      }),
    );
  });
  let stopping = false;
  const shutdown = async (signal: string) => {
    if (stopping) return;
    stopping = true;
    console.info(JSON.stringify({ level: "info", event: "provider_gateway_stopping", signal }));
    try {
      await runtime.shutdown(server);
      process.exitCode = 0;
    } catch (error) {
      console.error(
        JSON.stringify({
          level: "error",
          event: "provider_gateway_shutdown_failed",
          message: error instanceof Error ? error.message : String(error),
        }),
      );
      process.exitCode = 1;
    }
  };
  process.once("SIGINT", () => void shutdown("SIGINT"));
  process.once("SIGTERM", () => void shutdown("SIGTERM"));
}
