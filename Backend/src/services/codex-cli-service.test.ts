import { describe, expect, it } from "vitest";
import { parseCodexDeviceAuthChallenge } from "./codex-cli-service.js";

describe("CodexCliService", () => {
  it("parses Codex device-auth output without leaking ANSI codes", () => {
    const parsed = parseCodexDeviceAuthChallenge(`
Welcome to Codex [v\u001b[90m0.136.0\u001b[0m]
Follow these steps to sign in with ChatGPT using device code authorization:

1. Open this link in your browser and sign in to your account
   \u001b[94mhttps://auth.openai.com/codex/device\u001b[0m

2. Enter this one-time code \u001b[90m(expires in 15 minutes)\u001b[0m
   \u001b[94m82FL-NK4V4\u001b[0m
`);

    expect(parsed).toMatchObject({
      verificationUri: "https://auth.openai.com/codex/device",
      userCode: "82FL-NK4V4",
      intervalSeconds: 5,
    });
    expect(parsed?.expiresAt.getTime()).toBeGreaterThan(Date.now() + 14 * 60_000);
  });

  it("returns null for unrelated output", () => {
    expect(parseCodexDeviceAuthChallenge("Not logged in")).toBeNull();
  });
});
