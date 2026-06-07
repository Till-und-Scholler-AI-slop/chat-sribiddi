import { describe, expect, it } from "vitest";
import { parseCodexExecJsonl } from "./codex-exec-runtime.js";

describe("Codex exec runtime", () => {
  it("extracts the completed assistant message and usage from JSONL", () => {
    const parsed = parseCodexExecJsonl(
      [
        JSON.stringify({ type: "thread.started", thread_id: "thread-1" }),
        JSON.stringify({
          type: "item.completed",
          item: { id: "item-1", type: "agent_message", text: "Hallo aus Codex" },
        }),
        JSON.stringify({
          type: "turn.completed",
          usage: { input_tokens: 12, output_tokens: 7, total_tokens: 19 },
        }),
      ].join("\n"),
    );

    expect(parsed).toEqual({
      text: "Hallo aus Codex",
      error: null,
      usage: { inputTokens: 12, outputTokens: 7, totalTokens: 19 },
    });
  });

  it("sanitizes device codes in error events", () => {
    const parsed = parseCodexExecJsonl(
      JSON.stringify({ type: "error", message: "Login failed for ABCD-1234" }),
    );

    expect(parsed.error).toBe("Login failed for [device-code]");
  });
});
