import { describe, expect, it, vi } from "vitest";
import { env } from "../config/env.js";
import { AiChatService } from "./ai-chat-service.js";
import type { GrokCliBridge } from "./grok-cli-bridge-service.js";

const limits = {
  maxAttachments: 0,
  maxAttachmentBytes: 1,
  maxTotalAttachmentBytes: 1,
  contextMessageLimit: 10,
  parallelStreams: 1,
};

describe("AiChatService", () => {
  it("routes only rollout-selected providers through ProviderGateway", async () => {
    const gateway = {
      stream: vi.fn(() => ({
        textStream: (async function* () {
          yield "from gateway";
        })(),
        usage: Promise.resolve({ inputTokens: 1, outputTokens: 2, totalTokens: 3 }),
        providerStarted: Promise.resolve(),
      })),
    };
    const service = new AiChatService(undefined, gateway, new Set(["openrouter"]));
    const input = {
      provider: "OPENROUTER" as const,
      modelId: "openrouter/free",
      credential: {
        apiKey: "router-key",
        credentialSource: "USER_KEY" as const,
        limits,
      },
      messages: [{ role: "user" as const, content: "hello" }],
    };

    const stream = service.stream(input);
    await expect(stream.providerStarted).resolves.toBeUndefined();
    let text = "";
    for await (const chunk of stream.textStream) text += chunk;
    expect(text).toBe("from gateway");
    expect(gateway.stream).toHaveBeenCalledWith(input);

    await expect(service.generate(input)).resolves.toMatchObject({
      text: "from gateway",
      usage: { totalTokens: 3 },
    });
    expect(gateway.stream).toHaveBeenCalledTimes(2);
  });

  it("exposes an immediately resolved providerStarted promise on a direct rollout path", async () => {
    const bridge: GrokCliBridge = {
      startDeviceLogin: vi.fn(),
      pollDeviceLogin: vi.fn(),
      generate: vi.fn(async () => ({ text: "direct", usage: null })),
    };
    const service = new AiChatService(bridge, { stream: vi.fn() }, new Set());
    const result = service.stream({
      userId: "user-1",
      provider: "xai",
      modelId: "grok-build",
      credential: {
        apiKey: null,
        credentialSource: "USER_SUBSCRIPTION_CLI",
        limits,
        connection: {
          connectionId: 1,
          providerId: "xai",
          connectionProfileId: "grok-build-subscription",
          authMethod: "SUBSCRIPTION_CLI",
          modelId: "grok-build",
          cliHomeDir: "/private/grok/user-1",
          cliSessionId: "session-1",
          cliBridge: "grok-build",
        },
      },
      messages: [{ role: "user", content: "hello" }],
    });

    await expect(result.providerStarted).resolves.toBeUndefined();
    let text = "";
    for await (const chunk of result.textStream) text += chunk;
    expect(text).toBe("direct");
  });

  it("routes xAI Grok subscription requests through the per-user CLI bridge", async () => {
    const bridge: GrokCliBridge = {
      startDeviceLogin: vi.fn(),
      pollDeviceLogin: vi.fn(),
      generate: vi.fn(async () => ({ text: "from grok", usage: null })),
    };
    const service = new AiChatService(bridge);

    const result = await service.generate({
      userId: "user-1",
      provider: "xai",
      modelId: "grok-build",
      credential: {
        apiKey: null,
        credentialSource: "USER_SUBSCRIPTION_CLI",
        limits,
        connection: {
          connectionId: 1,
          providerId: "xai",
          connectionProfileId: "grok-build-subscription",
          authMethod: "SUBSCRIPTION_CLI",
          modelId: "grok-build",
          cliHomeDir: "/private/grok/user-1",
          cliSessionId: "session-1",
          cliBridge: "grok-build",
        },
      },
      messages: [{ role: "user", content: "hello" }],
    });

    expect(result.text).toBe("from grok");
    expect(bridge.generate).toHaveBeenCalledWith({
      userId: "user-1",
      session: {
        cliHomeDir: "/private/grok/user-1",
        cliSessionId: "session-1",
        modelId: "grok-build",
      },
      modelId: "grok-build",
      messages: [{ role: "user", content: "hello" }],
    });
  });

  it("propagates a browser abort through a direct rollout stream", async () => {
    const bridge: GrokCliBridge = {
      startDeviceLogin: vi.fn(),
      pollDeviceLogin: vi.fn(),
      generate: vi.fn(async () => new Promise<never>(() => undefined)),
    };
    const service = new AiChatService(bridge, { stream: vi.fn() }, new Set());
    const controller = new AbortController();
    const result = service.stream({
      ...grokRequest(),
      abortSignal: controller.signal,
    });
    const consume = (async () => {
      for await (const _chunk of result.textStream) {
        // The mocked direct provider never produces a chunk.
      }
    })();
    const aborted = expect(consume).rejects.toThrow("browser cancelled");

    controller.abort(new Error("browser cancelled"));

    await aborted;
  });

  it("enforces the backend deadline on a direct rollout generate request", async () => {
    vi.useFakeTimers();
    try {
      const bridge: GrokCliBridge = {
        startDeviceLogin: vi.fn(),
        pollDeviceLogin: vi.fn(),
        generate: vi.fn(async () => new Promise<never>(() => undefined)),
      };
      const service = new AiChatService(bridge, { stream: vi.fn() }, new Set());
      const generate = service.generate(grokRequest());
      const timedOut = expect(generate).rejects.toThrow("configured deadline");

      await vi.advanceTimersByTimeAsync(Math.min(env.CHAT_STREAM_TIMEOUT_MS, 30 * 60_000));

      await timedOut;
    } finally {
      vi.useRealTimers();
    }
  });
});

function grokRequest() {
  return {
    userId: "user-1",
    provider: "xai" as const,
    modelId: "grok-build",
    credential: {
      apiKey: null,
      credentialSource: "USER_SUBSCRIPTION_CLI" as const,
      limits,
      connection: {
        connectionId: 1,
        providerId: "xai",
        connectionProfileId: "grok-build-subscription",
        authMethod: "SUBSCRIPTION_CLI" as const,
        modelId: "grok-build",
        cliHomeDir: "/private/grok/user-1",
        cliSessionId: "session-1",
        cliBridge: "grok-build" as const,
      },
    },
    messages: [{ role: "user" as const, content: "hello" }],
  };
}
