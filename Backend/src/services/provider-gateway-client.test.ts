import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import {
  formatProviderSseEvent,
  PROVIDER_CONTRACT_VERSION,
  type ProviderStreamEvent,
  type ProviderStreamRequest,
} from "@chat-sribiddi/provider-contract";
import { tool } from "ai";
import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import type { ResolvedCredential } from "./credential-access-service.js";
import type { ResolvedProviderConnection } from "./provider-connection-service.js";
import {
  gatewayMessages,
  gatewayProvider,
  ProviderGatewayClient,
  type ProviderGatewayExecutionRequest,
  type ProviderGatewayStreamResult,
  shouldUseProviderGateway,
} from "./provider-gateway-client.js";

const limits = {
  maxAttachments: 5,
  maxAttachmentBytes: 5_242_880,
  maxTotalAttachmentBytes: 20_971_520,
  contextMessageLimit: 20,
  parallelStreams: 1,
};

const servers: Array<ReturnType<typeof createServer>> = [];

afterEach(async () => {
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          server.closeAllConnections?.();
          server.close(() => resolve());
        }),
    ),
  );
});

describe("ProviderGatewayClient", () => {
  it("authenticates, maps OAuth and multimodal messages, and parses arbitrarily chunked SSE", async () => {
    let receivedAuthorization = "";
    let received: ProviderStreamRequest | undefined;
    const baseUrl = await startMock(async (request, response) => {
      receivedAuthorization = request.headers.authorization ?? "";
      received = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      const stream = [
        event(received.requestId, 0, "provider_started", {
          provider: "google",
          modelId: received.modelId,
        }),
        ": heartbeat\n\n",
        event(received.requestId, 1, "text_delta", { delta: "Grüße" }),
        event(received.requestId, 2, "usage", {
          inputTokens: 4,
          outputTokens: 2,
          totalTokens: 6,
        }),
        event(received.requestId, 3, "done", { finishReason: "stop" }),
      ].join("");
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      const bytes = Buffer.from(stream);
      for (let offset = 0; offset < bytes.length; offset += 3) {
        response.write(bytes.subarray(offset, offset + 3));
      }
      response.end();
    });
    const client = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" });
    const result = client.stream({
      provider: "GOOGLE",
      modelId: "gemini-test",
      credential: credential({
        providerId: "google",
        authMethod: "OAUTH_PKCE",
        accessToken: "oauth-token",
        quotaProjectId: "quota-project",
      }),
      messages: [
        { role: "system", content: "Be concise" },
        {
          role: "user",
          content: [
            { type: "text", text: "inspect" },
            {
              type: "image",
              image: Buffer.from("image"),
              mediaType: "image/png",
            },
            {
              type: "file",
              data: Buffer.from("file"),
              mediaType: "application/pdf",
              filename: "brief.pdf",
            },
          ],
        },
      ],
    });

    await expect(result.providerStarted).resolves.toBeUndefined();
    await expect(collectText(result)).resolves.toBe("Grüße");
    await expect(result.usage).resolves.toMatchObject({
      inputTokens: 4,
      outputTokens: 2,
      totalTokens: 6,
    });
    expect(receivedAuthorization).toBe("Bearer gateway-secret");
    expect(received?.provider).toEqual({
      kind: "google",
      credential: {
        type: "oauth",
        accessToken: "oauth-token",
        quotaProjectId: "quota-project",
      },
    });
    expect(received?.messages).toEqual([
      { role: "system", content: "Be concise" },
      {
        role: "user",
        content: [
          { type: "text", text: "inspect" },
          { type: "image", data: Buffer.from("image").toString("base64"), mediaType: "image/png" },
          {
            type: "file",
            data: Buffer.from("file").toString("base64"),
            mediaType: "application/pdf",
            filename: "brief.pdf",
          },
        ],
      },
    ]);
  });

  it("orchestrates validated backend tools and sends native tool-call/result history", async () => {
    const execute = vi.fn(({ left, right }: { left: number; right: number }) => left + right);
    const requests: ProviderStreamRequest[] = [];
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      requests.push(body);
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      if (requests.length === 1) {
        response.write(
          event(body.requestId, 1, "tool_call", {
            toolCallId: "call-1",
            toolName: "add",
            input: { left: 2, right: 3 },
          }),
        );
        response.write(event(body.requestId, 2, "usage", { inputTokens: 2, outputTokens: 1 }));
        response.end(event(body.requestId, 3, "done", { finishReason: "tool-calls" }));
      } else {
        response.write(event(body.requestId, 1, "text_delta", { delta: "The result is 5" }));
        response.write(event(body.requestId, 2, "usage", { inputTokens: 3, outputTokens: 2 }));
        response.end(event(body.requestId, 3, "done", { finishReason: "stop" }));
      }
    });
    const client = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" });
    const result = client.stream({
      provider: "OPENROUTER",
      modelId: "openrouter/free",
      credential: credential(undefined, "router-key"),
      messages: [{ role: "user", content: "add 2 and 3" }],
      tools: {
        add: tool({
          description: "Add two numbers",
          inputSchema: z.object({ left: z.number(), right: z.number() }),
          execute,
        }),
      },
    });

    await expect(collectText(result)).resolves.toBe("The result is 5");
    await expect(result.usage).resolves.toMatchObject({
      inputTokens: 5,
      outputTokens: 3,
      totalTokens: null,
    });
    expect(execute).toHaveBeenCalledOnce();
    expect(requests).toHaveLength(2);
    expect(requests[0]?.tools?.[0]).toMatchObject({
      name: "add",
      description: "Add two numbers",
      inputSchema: { type: "object" },
    });
    expect(requests[1]?.messages.slice(-2)).toEqual([
      {
        role: "assistant",
        content: [
          {
            type: "tool-call",
            toolCallId: "call-1",
            toolName: "add",
            input: { left: 2, right: 3 },
          },
        ],
      },
      {
        role: "tool",
        content: [
          {
            type: "tool-result",
            toolCallId: "call-1",
            toolName: "add",
            output: 5,
          },
        ],
      },
    ]);
  });

  it("rejects invalid tool arguments without executing the tool or starting another model step", async () => {
    const execute = vi.fn(() => "unsafe");
    let requests = 0;
    const baseUrl = await startMock(async (request, response) => {
      requests += 1;
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.write(
        event(body.requestId, 1, "tool_call", {
          toolCallId: "call-1",
          toolName: "lookup",
          input: { query: 42 },
        }),
      );
      response.end(event(body.requestId, 2, "done", { finishReason: "tool-calls" }));
    });
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream({
      provider: "OPENROUTER",
      modelId: "openrouter/free",
      credential: credential(undefined, "router-key"),
      messages: [{ role: "user", content: "lookup" }],
      tools: {
        lookup: tool({ inputSchema: z.object({ query: z.string() }), execute }),
      },
    });

    await expect(result.providerStarted).resolves.toBeUndefined();
    await expect(collectText(result)).rejects.toMatchObject({ code: "invalid_tool_arguments" });
    expect(execute).not.toHaveBeenCalled();
    expect(requests).toBe(1);
  });

  it("stops a continuing tool loop after five model steps", async () => {
    let requests = 0;
    const execute = vi.fn(() => ({ ok: true }));
    const baseUrl = await startMock(async (request, response) => {
      requests += 1;
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.write(
        event(body.requestId, 1, "tool_call", {
          toolCallId: `call-${requests}`,
          toolName: "continue",
          input: {},
        }),
      );
      response.end(event(body.requestId, 2, "done", { finishReason: "tool-calls" }));
    });
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream({
      ...openRouterRequest(),
      tools: {
        continue: tool({ inputSchema: z.object({}), execute }),
      },
    });

    await expect(result.providerStarted).resolves.toBeUndefined();
    await expect(collectText(result)).rejects.toMatchObject({ code: "tool_step_limit" });
    expect(requests).toBe(5);
    expect(execute).toHaveBeenCalledTimes(4);
  });

  it("preserves received text and fails when the stream ends without done", async () => {
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.end(event(body.requestId, 1, "text_delta", { delta: "partial" }));
    });
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream(
      openRouterRequest(),
    );
    const iterator = result.textStream[Symbol.asyncIterator]();

    await expect(iterator.next()).resolves.toEqual({ value: "partial", done: false });
    await expect(iterator.next()).rejects.toMatchObject({ code: "missing_done" });
    await expect(result.providerStarted).resolves.toBeUndefined();
  });

  it("rejects malformed SSE instead of accepting a corrupt stream", async () => {
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.end("event: text_delta\ndata: {not-json}\n\n");
    });
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream(
      openRouterRequest(),
    );

    await expect(result.providerStarted).resolves.toBeUndefined();
    await expect(collectText(result)).rejects.toMatchObject({ code: "malformed_gateway_sse" });
  });

  it("validates request identity and contiguous event sequence", async () => {
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.end(event(body.requestId, 2, "text_delta", { delta: "skipped sequence one" }));
    });
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream(
      openRouterRequest(),
    );

    await expect(result.providerStarted).resolves.toBeUndefined();
    await expect(collectText(result)).rejects.toMatchObject({ code: "invalid_gateway_sequence" });
  });

  it("distinguishes errors before and after provider_started", async () => {
    const preStartUrl = await startMock(async (_request, response) => {
      response.writeHead(503, { "content-type": "application/json" });
      response.end(
        JSON.stringify({
          contractVersion: "1",
          error: { code: "gateway_draining", message: "draining", retryable: true },
        }),
      );
    });
    const preStart = new ProviderGatewayClient({
      baseUrl: preStartUrl,
      sharedSecret: "gateway-secret",
    }).stream(openRouterRequest());
    await expect(preStart.providerStarted).rejects.toMatchObject({
      code: "gateway_draining",
      providerStarted: false,
    });
    await expect(collectText(preStart)).rejects.toMatchObject({ code: "gateway_draining" });

    const postStartUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.end(
        event(body.requestId, 1, "error", {
          code: "provider_error",
          message: "upstream failed",
          retryable: false,
        }),
      );
    });
    const postStart = new ProviderGatewayClient({
      baseUrl: postStartUrl,
      sharedSecret: "gateway-secret",
    }).stream(openRouterRequest());
    await expect(postStart.providerStarted).resolves.toBeUndefined();
    await expect(collectText(postStart)).rejects.toMatchObject({
      code: "provider_error",
      providerStarted: true,
    });
  });

  it("propagates browser abort to the active gateway response", async () => {
    let connectionClosed!: () => void;
    const closed = new Promise<void>((resolve) => {
      connectionClosed = resolve;
    });
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.once("close", connectionClosed);
    });
    const abort = new AbortController();
    const result = new ProviderGatewayClient({ baseUrl, sharedSecret: "gateway-secret" }).stream({
      ...openRouterRequest(),
      abortSignal: abort.signal,
    });
    const consuming = collectText(result);

    await result.providerStarted;
    abort.abort();
    await expect(consuming).rejects.toMatchObject({ code: "request_aborted" });
    await expect(closed).resolves.toBeUndefined();
  });

  it("releases a producer blocked by backpressure when the request is aborted", async () => {
    const baseUrl = await startMock(async (request, response) => {
      const body = JSON.parse(await readRequest(request)) as ProviderStreamRequest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(
        event(body.requestId, 0, "provider_started", {
          provider: "openrouter",
          modelId: body.modelId,
        }),
      );
      response.write(event(body.requestId, 1, "text_delta", { delta: "first" }));
      response.write(event(body.requestId, 2, "text_delta", { delta: "second" }));
    });
    const abort = new AbortController();
    const result = new ProviderGatewayClient({
      baseUrl,
      sharedSecret: "gateway-secret",
      maxQueueChunks: 1,
      maxQueueBytes: 1_024,
    }).stream({
      ...openRouterRequest(),
      abortSignal: abort.signal,
    });

    await result.providerStarted;
    await new Promise((resolve) => setTimeout(resolve, 25));
    abort.abort();

    await expect(withTimeout(result.usage, 500)).rejects.toMatchObject({
      code: "request_aborted",
    });
  });
});

describe("ProviderGateway request mapping and rollout", () => {
  it("maps API keys, compatible endpoints, Codex, and Grok credentials without caching them", () => {
    expect(gatewayProvider(openRouterRequest())).toMatchObject({
      kind: "openrouter",
      apiKey: "router-key",
    });
    expect(
      gatewayProvider({
        ...openRouterRequest(),
        provider: "xai",
        credential: credential(
          {
            providerId: "xai",
            authMethod: "OPENAI_COMPATIBLE",
            baseUrl: "https://api.x.ai/v1",
            headers: { "x-tenant": "tenant" },
          },
          "xai-key",
        ),
      }),
    ).toEqual({
      kind: "openai-compatible",
      providerId: "xai",
      baseUrl: "https://api.x.ai/v1",
      apiKey: "xai-key",
      headers: { "x-tenant": "tenant" },
    });
    expect(
      gatewayProvider({
        ...openRouterRequest(),
        provider: "OPENAI",
        credential: credential({
          providerId: "openai",
          authMethod: "SUBSCRIPTION_CLI",
          codexHome: "/sessions/codex/user-1",
        }),
      }),
    ).toEqual({ kind: "codex", codexHome: "/sessions/codex/user-1" });
    expect(
      gatewayProvider({
        ...openRouterRequest(),
        provider: "xai",
        credential: credential({
          providerId: "xai",
          authMethod: "SUBSCRIPTION_CLI",
          cliHomeDir: "/sessions/grok/user-1",
        }),
      }),
    ).toEqual({ kind: "grok-cli", cliHomeDir: "/sessions/grok/user-1" });
  });

  it("keeps rollout disabled by an empty set and supports provider-specific and all flags", () => {
    const request = openRouterRequest();
    expect(shouldUseProviderGateway(request, new Set())).toBe(false);
    expect(shouldUseProviderGateway(request, new Set(["openrouter"]))).toBe(true);
    expect(
      shouldUseProviderGateway({ ...request, provider: "OPENAI" }, new Set(["openrouter"])),
    ).toBe(false);
    expect(shouldUseProviderGateway({ ...request, provider: "OPENAI" }, new Set(["all"]))).toBe(
      true,
    );
  });

  it("maps existing assistant tool calls and tool results as native history", () => {
    expect(
      gatewayMessages([
        {
          role: "assistant",
          content: [
            { type: "text", text: "Checking" },
            { type: "tool-call", toolCallId: "call-1", toolName: "lookup", input: { q: "x" } },
          ],
        },
        {
          role: "tool",
          content: [
            {
              type: "tool-result",
              toolCallId: "call-1",
              toolName: "lookup",
              output: { type: "json", value: { answer: 42 } },
            },
          ],
        },
      ]),
    ).toEqual([
      {
        role: "assistant",
        content: [
          { type: "text", text: "Checking" },
          { type: "tool-call", toolCallId: "call-1", toolName: "lookup", input: { q: "x" } },
        ],
      },
      {
        role: "tool",
        content: [
          {
            type: "tool-result",
            toolCallId: "call-1",
            toolName: "lookup",
            output: { answer: 42 },
          },
        ],
      },
    ]);
  });
});

function credential(
  connection?: Partial<ResolvedProviderConnection> &
    Pick<ResolvedProviderConnection, "providerId" | "authMethod">,
  apiKey: string | null = null,
): ResolvedCredential {
  const resolvedConnection = connection
    ? {
        connectionId: 1,
        connectionProfileId: "test-profile",
        ...connection,
      }
    : undefined;
  return {
    apiKey,
    credentialSource:
      connection?.authMethod === "SUBSCRIPTION_CLI" ? "USER_SUBSCRIPTION_CLI" : "USER_KEY",
    limits,
    ...(resolvedConnection ? { connection: resolvedConnection } : {}),
  };
}

function openRouterRequest(): ProviderGatewayExecutionRequest {
  return {
    provider: "OPENROUTER",
    modelId: "openrouter/free",
    credential: credential(undefined, "router-key"),
    messages: [{ role: "user", content: "hello" }],
  };
}

async function collectText(result: ProviderGatewayStreamResult) {
  let text = "";
  for await (const chunk of result.textStream) text += chunk;
  return text;
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number) {
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error("Promise did not settle in time")),
      timeoutMs,
    );
    void promise.then(resolve, reject).finally(() => clearTimeout(timeout));
  });
}

async function startMock(
  handler: (request: IncomingMessage, response: ServerResponse) => Promise<void>,
) {
  const server = createServer((request, response) => {
    void handler(request, response).catch((error) => {
      response.destroy(error instanceof Error ? error : new Error(String(error)));
    });
  });
  servers.push(server);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address() as AddressInfo;
  return `http://127.0.0.1:${address.port}`;
}

async function readRequest(request: IncomingMessage) {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks).toString("utf8");
}

function event(
  requestId: string,
  sequence: number,
  type: ProviderStreamEvent["type"],
  data: Record<string, unknown>,
) {
  return formatProviderSseEvent({
    contractVersion: PROVIDER_CONTRACT_VERSION,
    requestId,
    sequence,
    timestamp: new Date().toISOString(),
    type,
    ...data,
  } as ProviderStreamEvent);
}
