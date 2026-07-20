import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import test from "node:test";
import {
  PROVIDER_CONTRACT_VERSION,
  PROVIDER_MODEL_CATALOG_PATH,
  PROVIDER_STREAM_PATH,
  type ProviderStreamEvent,
  ProviderStreamEventSchema,
  type ProviderStreamRequest,
} from "@chat-sribiddi/provider-contract";
import { formatCliPrompt, safeCodexEnvironment, toModelMessages } from "./providers.js";
import {
  createProviderGateway,
  createProviderGatewayServer,
  parseCodexExecJsonl,
  validateOpenAiCompatibleBaseUrl,
} from "./server.js";

const SHARED_SECRET = "provider-gateway-test-secret";

test("validateOpenAiCompatibleBaseUrl rejects unsafe endpoints unless explicitly enabled", () => {
  assert.throws(
    () => validateOpenAiCompatibleBaseUrl("http://127.0.0.1:1234/v1", false),
    /Private or local provider endpoints are disabled/,
  );
  assert.equal(
    validateOpenAiCompatibleBaseUrl("http://127.0.0.1:1234/v1", true),
    "http://127.0.0.1:1234/v1",
  );
  assert.throws(
    () => validateOpenAiCompatibleBaseUrl("file:///tmp/provider", true),
    /must use http or https/,
  );
  assert.throws(
    () => validateOpenAiCompatibleBaseUrl("https://user:secret@example.com/v1", true),
    /must not include credentials/,
  );
});

test("health endpoints are public while provider and metrics endpoints require the shared secret", async () => {
  const gateway = await listen(createProviderGatewayServer(gatewayOptions()));
  try {
    const live = await fetch(`${gateway.url}/health/live`);
    assert.equal(live.status, 200);
    assert.deepEqual(await live.json(), {
      status: "up",
      service: "provider-gateway",
      contractVersion: PROVIDER_CONTRACT_VERSION,
    });
    assert.equal((await fetch(`${gateway.url}/health/ready`)).status, 200);

    const metrics = await fetch(`${gateway.url}/metrics`);
    assert.equal(metrics.status, 401);
    assert.match(metrics.headers.get("www-authenticate") ?? "", /Bearer/);

    const catalog = await fetch(`${gateway.url}${PROVIDER_MODEL_CATALOG_PATH}?provider=openrouter`);
    assert.equal(catalog.status, 401);

    const provider = await fetch(`${gateway.url}${PROVIDER_STREAM_PATH}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(providerRequest()),
    });
    assert.equal(provider.status, 401);
    assert.equal((await provider.json()).error.code, "unauthorized");
  } finally {
    await close(gateway.server);
  }
});

test("gateway rejects incompatible contracts and oversized bodies before provider execution", async () => {
  let executions = 0;
  const gateway = await listen(
    createProviderGatewayServer({
      ...gatewayOptions(),
      config: { sharedSecret: SHARED_SECRET, bodyLimitBytes: 256 },
      executor: async () => {
        executions += 1;
        return { finishReason: "stop" };
      },
    }),
  );
  try {
    const invalid = await fetch(`${gateway.url}${PROVIDER_STREAM_PATH}`, {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: JSON.stringify({ ...providerRequest(), contractVersion: "2" }),
    });
    assert.equal(invalid.status, 400);
    assert.equal((await invalid.json()).error.code, "invalid_contract");

    const oversized = await fetch(`${gateway.url}${PROVIDER_STREAM_PATH}`, {
      method: "POST",
      headers: authenticatedJsonHeaders(),
      body: JSON.stringify({ padding: "x".repeat(512) }),
    });
    assert.equal(oversized.status, 413);
    assert.equal((await oversized.json()).error.code, "payload_too_large");
    assert.equal(executions, 0);
  } finally {
    await close(gateway.server);
  }
});

test("gateway emits the versioned internal SSE event sequence", async () => {
  const gateway = await listen(
    createProviderGatewayServer({
      ...gatewayOptions(),
      executor: async (request, sink, signal) => {
        assert.equal(request.requestId, "request-test-1");
        assert.equal(signal.aborted, false);
        await sink.reasoning("checking");
        await sink.text("hello");
        await sink.toolCall("call-1", "lookup_weather", { city: "Berlin" });
        await sink.usage({
          inputTokens: 4,
          outputTokens: 6,
          totalTokens: 10,
          reasoningTokens: 2,
          cachedInputTokens: 1,
        });
        return { finishReason: "stop" };
      },
    }),
  );
  try {
    const response = await authenticatedProviderFetch(gateway.url, providerRequest());
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);

    const events = parseSseEvents(await response.text());
    assert.deepEqual(
      events.map((event) => event.type),
      ["provider_started", "reasoning_delta", "text_delta", "tool_call", "usage", "done"],
    );
    assert.deepEqual(
      events.map((event) => event.sequence),
      [0, 1, 2, 3, 4, 5],
    );
    assert.ok(events.every((event) => event.contractVersion === PROVIDER_CONTRACT_VERSION));
    assert.ok(events.every((event) => event.requestId === "request-test-1"));
    assert.deepEqual(
      events.find((event) => event.type === "tool_call"),
      {
        contractVersion: "1",
        requestId: "request-test-1",
        sequence: 3,
        timestamp: events[3]?.timestamp,
        type: "tool_call",
        toolCallId: "call-1",
        toolName: "lookup_weather",
        input: { city: "Berlin" },
      },
    );

    const metrics = await fetch(`${gateway.url}/metrics`, {
      headers: { authorization: `Bearer ${SHARED_SECRET}` },
    });
    assert.equal(metrics.status, 200);
    const metricsBody = await metrics.text();
    assert.match(
      metricsBody,
      /provider_gateway_streams_total\{outcome="success",provider="openai-compatible"\} 1/,
    );
    assert.match(metricsBody, /provider_gateway_active_streams 0/);
    assert.match(
      metricsBody,
      /provider_gateway_ttft_seconds_count\{provider="openai-compatible"\} 1/,
    );
    assert.match(
      metricsBody,
      /provider_gateway_ttft_seconds_sum\{provider="openai-compatible"\} [0-9.]+/,
    );
  } finally {
    await close(gateway.server);
  }
});

test("gateway emits heartbeat comments while a provider is idle", async () => {
  const gateway = await listen(
    createProviderGatewayServer({
      config: { sharedSecret: SHARED_SECRET, heartbeatIntervalMs: 10 },
      executor: async (_request, sink) => {
        await wait(35);
        await sink.text("awake");
        return { finishReason: "stop" };
      },
    }),
  );
  try {
    const response = await authenticatedProviderFetch(gateway.url, providerRequest());
    assert.equal(response.status, 200);
    const body = await response.text();
    assert.match(body, /: heartbeat\n\n/);
    assert.deepEqual(
      parseSseEvents(body).map((event) => event.type),
      ["provider_started", "text_delta", "done"],
    );
  } finally {
    await close(gateway.server);
  }
});

test("gateway aborts providers at the configured deadline and emits a terminal error", async () => {
  const gateway = await listen(
    createProviderGatewayServer({
      config: { sharedSecret: SHARED_SECRET, maxDeadlineMs: 25 },
      executor: async (_request, _sink, signal) => {
        return new Promise<never>((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(signal.reason), { once: true });
        });
      },
    }),
  );
  try {
    const response = await authenticatedProviderFetch(gateway.url, {
      ...providerRequest(),
      timeoutMs: 1_000,
    });
    assert.equal(response.status, 200);
    const events = parseSseEvents(await response.text());
    assert.deepEqual(
      events.map((event) => event.type),
      ["provider_started", "error"],
    );
    const error = events.at(-1);
    assert.equal(error?.type, "error");
    if (error?.type === "error") {
      assert.equal(error.code, "provider_timeout");
      assert.equal(error.retryable, true);
    }
  } finally {
    await close(gateway.server);
  }
});

test("gateway streams an OpenAI-compatible upstream through the native adapter", async () => {
  const provider = await listen(fakeOpenAiCompatibleProvider());
  const gateway = await listen(
    createProviderGatewayServer({
      config: { sharedSecret: SHARED_SECRET, allowPrivateBaseUrls: true },
    }),
  );
  try {
    const response = await authenticatedProviderFetch(gateway.url, {
      ...providerRequest(),
      provider: {
        kind: "openai-compatible",
        providerId: "test",
        baseUrl: `${provider.url}/v1`,
        apiKey: "sk-user-secret",
      },
    });
    assert.equal(response.status, 200);
    const events = parseSseEvents(await response.text());
    assert.equal(
      events
        .filter((event) => event.type === "text_delta")
        .map((event) => event.delta)
        .join(""),
      "hello world",
    );
    const usage = events.find((event) => event.type === "usage");
    assert.equal(usage?.type, "usage");
    if (usage?.type === "usage") assert.equal(usage.totalTokens, 4);
    assert.equal(events.at(-1)?.type, "done");
  } finally {
    await close(gateway.server);
    await close(provider.server);
  }
});

test("gateway sends Google OAuth as Bearer auth without leaking it as an API-key header", async () => {
  const observed: Record<string, string | undefined> = {};
  const provider = await listen(fakeGoogleProvider(observed));
  const gateway = await listen(
    createProviderGatewayServer({
      config: { sharedSecret: SHARED_SECRET, googleBaseUrl: `${provider.url}/v1beta` },
    }),
  );
  try {
    const response = await authenticatedProviderFetch(gateway.url, {
      ...providerRequest(),
      provider: {
        kind: "google",
        credential: {
          type: "oauth",
          accessToken: "google-oauth-access-token",
          quotaProjectId: "quota-project-1",
        },
      },
    });
    assert.equal(response.status, 200);
    const events = parseSseEvents(await response.text());
    assert.equal(
      events
        .filter((event) => event.type === "text_delta")
        .map((event) => event.delta)
        .join(""),
      "hello oauth",
    );
    assert.equal(events.at(-1)?.type, "done");
    assert.equal(observed.authorization, "Bearer google-oauth-access-token");
    assert.equal(observed.apiKey, undefined);
    assert.equal(observed.quotaProject, "quota-project-1");
    assert.match(observed.url ?? "", /\/v1beta\/models\/test-model:streamGenerateContent\?alt=sse/);
  } finally {
    await close(gateway.server);
    await close(provider.server);
  }
});

test("maps multimodal and tool-history contract messages to AI SDK messages", () => {
  const messages: ProviderStreamRequest["messages"] = [
    { role: "system", content: "Be concise." },
    {
      role: "user",
      content: [
        { type: "text", text: "Inspect this" },
        { type: "image", data: "aGVsbG8=", mediaType: "image/png", filename: "photo.png" },
        {
          type: "file",
          data: "eyJvayI6dHJ1ZX0=",
          mediaType: "application/json",
          filename: "input.json",
        },
      ],
    },
    {
      role: "assistant",
      content: [{ type: "tool-call", toolCallId: "call-1", toolName: "inspect", input: { id: 7 } }],
    },
    {
      role: "tool",
      content: [
        { type: "tool-result", toolCallId: "call-1", toolName: "inspect", output: { valid: true } },
        { type: "tool-result", toolCallId: "call-2", toolName: "explain", output: "finished" },
      ],
    },
  ];

  assert.deepEqual(toModelMessages(messages), [
    { role: "system", content: "Be concise." },
    {
      role: "user",
      content: [
        { type: "text", text: "Inspect this" },
        { type: "image", image: "aGVsbG8=", mediaType: "image/png", filename: "photo.png" },
        {
          type: "file",
          data: "eyJvayI6dHJ1ZX0=",
          mediaType: "application/json",
          filename: "input.json",
        },
      ],
    },
    {
      role: "assistant",
      content: [{ type: "tool-call", toolCallId: "call-1", toolName: "inspect", input: { id: 7 } }],
    },
    {
      role: "tool",
      content: [
        {
          type: "tool-result",
          toolCallId: "call-1",
          toolName: "inspect",
          output: { type: "json", value: { valid: true } },
        },
        {
          type: "tool-result",
          toolCallId: "call-2",
          toolName: "explain",
          output: { type: "text", value: "finished" },
        },
      ],
    },
  ]);
});

test("CLI prompts preserve tool history and reject unsupported binary attachment payloads", () => {
  const prompt = formatCliPrompt([
    { role: "user", content: "Use the tool" },
    {
      role: "assistant",
      content: [{ type: "tool-call", toolCallId: "call-1", toolName: "lookup", input: { id: 3 } }],
    },
    {
      role: "tool",
      content: [
        { type: "tool-result", toolCallId: "call-1", toolName: "lookup", output: { value: 9 } },
      ],
    },
  ]);
  assert.match(prompt, /tool-call id=call-1 name=lookup/);
  assert.match(prompt, /tool-result id=call-1 name=lookup/);

  assert.throws(
    () =>
      formatCliPrompt([
        {
          role: "user",
          content: [{ type: "image", data: "aGVsbG8=", mediaType: "image/png" }],
        },
      ]),
    /image content is not supported by CLI providers/,
  );
});

test("Codex CLI receives an isolated allowlisted environment", () => {
  const environment = safeCodexEnvironment("/var/lib/codex/user-1", {
    PATH: "/usr/local/bin:/usr/bin",
    LANG: "de_DE.UTF-8",
    RUST_LOG: "warn",
    HTTPS_PROXY: "https://proxy.example:8443",
    PROVIDER_GATEWAY_SHARED_SECRET: "must-not-reach-child",
    OPENAI_API_KEY: "must-not-reach-child",
  });

  assert.equal(environment.HOME, "/var/lib/codex/user-1");
  assert.equal(environment.CODEX_HOME, "/var/lib/codex/user-1");
  assert.equal(environment.PATH, "/usr/local/bin:/usr/bin");
  assert.equal(environment.LANG, "de_DE.UTF-8");
  assert.equal(environment.RUST_LOG, "warn");
  assert.equal(environment.HTTPS_PROXY, "https://proxy.example:8443");
  assert.equal(environment.PROVIDER_GATEWAY_SHARED_SECRET, undefined);
  assert.equal(environment.OPENAI_API_KEY, undefined);
});

test("readiness flips before graceful shutdown stops the listener", async () => {
  const { server, runtime } = createProviderGateway(gatewayOptions());
  const gateway = await listen(server);
  try {
    assert.equal((await fetch(`${gateway.url}/health/ready`)).status, 200);
    runtime.beginDrain();
    assert.equal((await fetch(`${gateway.url}/health/ready`)).status, 503);

    const rejected = await authenticatedProviderFetch(gateway.url, providerRequest());
    assert.equal(rejected.status, 503);
    assert.equal((await rejected.json()).error.code, "gateway_draining");

    await runtime.shutdown(server);
    assert.equal(server.listening, false);
  } finally {
    await close(server);
  }
});

test("parseCodexExecJsonl extracts assistant text, reasoning, and normalized usage", () => {
  const parsed = parseCodexExecJsonl(
    [
      JSON.stringify({ type: "thread.started", thread_id: "thread-1" }),
      JSON.stringify({ type: "item.completed", item: { type: "reasoning", text: "think" } }),
      JSON.stringify({
        type: "item.completed",
        item: { type: "agent_message", text: "hello from codex" },
      }),
      JSON.stringify({
        type: "turn.completed",
        usage: { input_tokens: 3, output_tokens: 4, total_tokens: 7 },
      }),
    ].join("\n"),
  );

  assert.deepEqual(parsed, {
    text: "hello from codex",
    reasoning: "think",
    error: null,
    usage: {
      inputTokens: 3,
      outputTokens: 4,
      totalTokens: 7,
      reasoningTokens: null,
      cachedInputTokens: null,
    },
  });
});

function gatewayOptions() {
  return {
    config: { sharedSecret: SHARED_SECRET },
    executor: async () => ({ finishReason: "stop" }),
  };
}

function providerRequest(): ProviderStreamRequest {
  return {
    contractVersion: PROVIDER_CONTRACT_VERSION,
    requestId: "request-test-1",
    provider: {
      kind: "openai-compatible",
      providerId: "test",
      baseUrl: "https://provider.example/v1",
      apiKey: "sk-test",
    },
    modelId: "test-model",
    messages: [{ role: "user", content: "hello" }],
  };
}

function authenticatedJsonHeaders() {
  return {
    authorization: `Bearer ${SHARED_SECRET}`,
    "content-type": "application/json",
  };
}

function authenticatedProviderFetch(url: string, body: ProviderStreamRequest) {
  return fetch(`${url}${PROVIDER_STREAM_PATH}`, {
    method: "POST",
    headers: authenticatedJsonHeaders(),
    body: JSON.stringify(body),
  });
}

function parseSseEvents(body: string): ProviderStreamEvent[] {
  return body
    .split(/\r?\n\r?\n/)
    .filter((frame) => frame.startsWith("event:"))
    .map((frame) => {
      const data = frame
        .split(/\r?\n/)
        .find((line) => line.startsWith("data: "))
        ?.slice("data: ".length);
      assert.ok(data, `SSE frame has no data field: ${frame}`);
      return ProviderStreamEventSchema.parse(JSON.parse(data));
    });
}

function fakeOpenAiCompatibleProvider() {
  return createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      response.writeHead(404).end();
      return;
    }
    await drain(request);
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
    });
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}\n\n',
    );
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}\n\n',
    );
    response.write(
      'data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}\n\n',
    );
    response.end("data: [DONE]\n\n");
  });
}

function fakeGoogleProvider(observed: Record<string, string | undefined>) {
  return createServer(async (request, response) => {
    observed.authorization = request.headers.authorization;
    observed.apiKey = headerValue(request.headers["x-goog-api-key"]);
    observed.quotaProject = headerValue(request.headers["x-goog-user-project"]);
    observed.url = request.url;
    await drain(request);
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
    });
    response.end(
      'data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hello oauth"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":2,"totalTokenCount":4}}\n\n',
    );
  });
}

function headerValue(value: string | string[] | undefined) {
  return Array.isArray(value) ? value.join(",") : value;
}

async function drain(request: NodeJS.ReadableStream) {
  for await (const _ of request) {
    // Drain the request body before writing the fake provider response.
  }
}

function wait(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

async function listen(server: Server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Expected TCP server address");
  return { server, url: `http://127.0.0.1:${address.port}` };
}

async function close(server: Server) {
  if (!server.listening) return;
  server.close();
  await once(server, "close");
}
