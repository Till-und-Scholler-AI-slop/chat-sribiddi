import assert from "node:assert/strict";
import test from "node:test";
import { loadGatewayConfig } from "./config.js";
import { ProviderModelCatalog } from "./model-catalog.js";

test("model catalog is secret-free and cached for repeated reads", async () => {
  let calls = 0;
  let observedAuthorization: string | null = null;
  const fetchImpl: typeof fetch = async (_input, init) => {
    calls += 1;
    observedAuthorization = new Headers(init?.headers).get("authorization");
    return new Response(
      JSON.stringify({
        data: [
          {
            id: "example/model",
            name: "Example Model",
            context_length: 128_000,
            pricing: { prompt: "0.000001", completion: "0.000002" },
          },
        ],
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    );
  };
  const catalog = new ProviderModelCatalog(
    loadGatewayConfig({
      sharedSecret: "catalog-test-secret",
      openRouterModelCatalogUrl: "https://catalog.example/models",
    }),
    fetchImpl,
  );

  const first = await catalog.list("openrouter");
  const second = await catalog.list("OPENROUTER");

  assert.equal(calls, 1);
  assert.equal(observedAuthorization, null);
  assert.deepEqual(second, first);
  assert.equal(first.stale, false);
  assert.deepEqual(first.models, [
    {
      id: "example/model",
      label: "Example Model",
      canonicalSlug: "example/model",
      created: null,
      description: null,
      contextLength: 128_000,
      maxCompletionTokens: null,
      promptPrice: "0.000001",
      completionPrice: "0.000002",
      requestPrice: null,
      imagePrice: null,
      modality: null,
      inputModalities: null,
      outputModalities: null,
      supportedParameters: null,
    },
  ]);
});

test("model catalog rejects provider types without a secret-free catalog adapter", async () => {
  const catalog = new ProviderModelCatalog(
    loadGatewayConfig({ sharedSecret: "catalog-test-secret" }),
    async () => new Response(JSON.stringify({ data: [] }), { status: 200 }),
  );

  await assert.rejects(
    () => catalog.list("openai"),
    (error: unknown) => {
      return error instanceof Error && error.message.includes("OpenRouter only");
    },
  );
});
