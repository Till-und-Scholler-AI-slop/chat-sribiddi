import assert from "node:assert/strict";
import test from "node:test";
import {
  PROVIDER_CONTRACT_VERSION,
  ProviderModelCatalogResponseSchema,
  ProviderStreamEventSchema,
  ProviderStreamRequestSchema,
  formatProviderSseEvent,
} from "../index.js";

const baseRequest = {
  contractVersion: PROVIDER_CONTRACT_VERSION,
  requestId: "request-1",
  modelId: "model-1",
  messages: [{ role: "user", content: "hello" }],
};

test("accepts every supported provider transport", () => {
  const providers = [
    { kind: "openai", apiKey: "sk-openai" },
    { kind: "anthropic", apiKey: "sk-anthropic" },
    { kind: "google", credential: { type: "api-key", apiKey: "google-key" } },
    { kind: "openrouter", apiKey: "sk-openrouter", appName: "chat-sribiddi" },
    {
      kind: "openai-compatible",
      providerId: "qwen",
      baseUrl: "https://example.com/v1",
      apiKey: "key",
    },
    { kind: "codex", codexHome: "/connections/user" },
    { kind: "grok-cli", cliHomeDir: "/sessions/user" },
  ];

  for (const provider of providers) {
    assert.equal(ProviderStreamRequestSchema.parse({ ...baseRequest, provider }).provider.kind, provider.kind);
  }
});

test("rejects incompatible contract versions", () => {
  const parsed = ProviderStreamRequestSchema.safeParse({
    ...baseRequest,
    contractVersion: "2",
    provider: { kind: "openai", apiKey: "key" },
  });
  assert.equal(parsed.success, false);
});

test("accepts Google API-key and OAuth credential variants", () => {
  const apiKey = ProviderStreamRequestSchema.parse({
    ...baseRequest,
    provider: { kind: "google", credential: { type: "api-key", apiKey: "google-key" } },
  });
  const oauth = ProviderStreamRequestSchema.parse({
    ...baseRequest,
    provider: {
      kind: "google",
      credential: { type: "oauth", accessToken: "oauth-token", quotaProjectId: "quota-project" },
    },
  });
  assert.equal(apiKey.provider.kind, "google");
  assert.equal(oauth.provider.kind, "google");
  assert.equal(oauth.provider.credential.type, "oauth");
});

test("validates messages without changing significant whitespace", () => {
  const parsed = ProviderStreamRequestSchema.parse({
    ...baseRequest,
    provider: { kind: "openai", apiKey: "key" },
    messages: [{ role: "user", content: "  preserve me  " }],
  });
  assert.equal(parsed.messages[0].content, "  preserve me  ");
  assert.equal(
    ProviderStreamRequestSchema.safeParse({
      ...baseRequest,
      provider: { kind: "openai", apiKey: "key" },
      messages: [{ role: "user", content: "   " }],
    }).success,
    false,
  );
});

test("accepts multimodal data and native tool history", () => {
  const parsed = ProviderStreamRequestSchema.parse({
    ...baseRequest,
    provider: { kind: "openai", apiKey: "key" },
    messages: [
      {
        role: "user",
        content: [
          { type: "text", text: "Describe these attachments" },
          { type: "image", data: "data:image/png;base64,aGVsbG8=", mediaType: "image/png", filename: "image.png" },
          { type: "file", data: "eyJvayI6dHJ1ZX0=", mediaType: "application/json", filename: "input.json" },
        ],
      },
      {
        role: "assistant",
        content: [
          { type: "text", text: "I will inspect it." },
          { type: "tool-call", toolCallId: "call-1", toolName: "inspect", input: { filename: "input.json" } },
        ],
      },
      {
        role: "tool",
        content: [
          { type: "tool-result", toolCallId: "call-1", toolName: "inspect", output: { valid: true } },
        ],
      },
    ],
  });

  assert.equal(parsed.messages[0].role, "user");
  assert.equal(parsed.messages[1].role, "assistant");
  assert.equal(parsed.messages[2].role, "tool");
});

test("rejects malformed binary data and role-incompatible parts", () => {
  const invalidBase64 = ProviderStreamRequestSchema.safeParse({
    ...baseRequest,
    provider: { kind: "openai", apiKey: "key" },
    messages: [
      { role: "user", content: [{ type: "image", data: "not base64!", mediaType: "image/png" }] },
    ],
  });
  assert.equal(invalidBase64.success, false);

  const toolCallInUserMessage = ProviderStreamRequestSchema.safeParse({
    ...baseRequest,
    provider: { kind: "openai", apiKey: "key" },
    messages: [
      {
        role: "user",
        content: [{ type: "tool-call", toolCallId: "call-1", toolName: "inspect", input: {} }],
      },
    ],
  });
  assert.equal(toolCallInUserMessage.success, false);
});

test("validates and formats typed SSE events", () => {
  const event = ProviderStreamEventSchema.parse({
    contractVersion: PROVIDER_CONTRACT_VERSION,
    requestId: "request-1",
    sequence: 2,
    timestamp: new Date().toISOString(),
    type: "text_delta",
    delta: "hello",
  });
  assert.match(formatProviderSseEvent(event), /^event: text_delta\nid: 2\ndata: /);
});

test("validates the secret-free model catalog response", () => {
  const catalog = ProviderModelCatalogResponseSchema.parse({
    contractVersion: PROVIDER_CONTRACT_VERSION,
    provider: "openrouter",
    fetchedAt: new Date().toISOString(),
    stale: false,
    models: [
      {
        id: "openrouter/free",
        label: "OpenRouter Free",
        contextLength: 131072,
        inputModalities: ["text", "image"],
      },
    ],
  });
  assert.equal(catalog.models[0].id, "openrouter/free");
  assert.equal(JSON.stringify(catalog).includes("apiKey"), false);
});
