import { describe, expect, it, vi } from "vitest";
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
});
