import { describe, expect, it } from "vitest";
import { ToolService } from "./tool-service.js";

describe("ToolService AI tools", () => {
  it("executes enabled calculator tool and records the call", async () => {
    const service = new ToolService(
      {
        findByIdAndUser: async () => ({ chat_id: "chat-1" }),
      } as never,
      {
        list: async () => [],
      } as never,
    );

    const executable = await service.aiTools("chat-1", "user-1");
    const calculator = executable.tools?.calculator;
    const result = await calculator?.execute?.({ expression: "2 + 3 * 4" }, {} as never);

    expect(result).toBe("14");
    expect(executable.records).toMatchObject([
      {
        toolName: "calculator",
        status: "SUCCESS",
        outputPayload: "14",
      },
    ]);
  });
});
