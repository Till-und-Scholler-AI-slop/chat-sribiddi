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
        clientId: expect.stringMatching(/^tool-/),
        toolName: "calculator",
        status: "SUCCESS",
        outputPayload: "14",
      },
    ]);
  });

  it("emits running and completed events while a tool executes", async () => {
    const service = new ToolService(
      {
        findByIdAndUser: async () => ({ chat_id: "chat-1" }),
      } as never,
      {
        list: async () => [],
      } as never,
    );
    const events: unknown[] = [];

    const executable = await service.aiTools("chat-1", "user-1", (event) => {
      events.push(event);
    });
    const result = await executable.tools?.calculator?.execute?.(
      { expression: "10 / 2" },
      {} as never,
    );

    expect(result).toBe("5");
    expect(events).toMatchObject([
      {
        toolName: "calculator",
        status: "RUNNING",
        outputPayload: null,
      },
      {
        toolName: "calculator",
        status: "SUCCESS",
        outputPayload: "5",
      },
    ]);
    expect(executable.records[0]?.clientId).toBe((events[0] as { clientId?: string }).clientId);
    expect((events[1] as { clientId?: string }).clientId).toBe(
      (events[0] as { clientId?: string }).clientId,
    );
  });

  it("exposes optional chart and spreadsheet tools when enabled for a chat", async () => {
    const service = new ToolService(
      {
        findByIdAndUser: async () => ({ chat_id: "chat-1" }),
      } as never,
      {
        list: async () => [
          { tool_name: "chart-generator", enabled: true },
          { tool_name: "spreadsheet-analyzer", enabled: true },
        ],
      } as never,
    );

    const executable = await service.aiTools("chat-1", "user-1");
    const chart = await executable.tools?.["chart-generator"]?.execute?.(
      {
        title: "Revenue",
        chartType: "bar",
        data: [
          { label: "Q1", value: 12 },
          { label: "Q2", value: 18 },
        ],
      },
      {} as never,
    );
    const table = await executable.tools?.["spreadsheet-analyzer"]?.execute?.(
      {
        data: "name,revenue\nA,12\nB,18",
        delimiter: "auto",
        hasHeader: true,
      },
      {} as never,
    );

    expect(JSON.parse(String(chart))).toMatchObject({
      type: "chart",
      title: "Revenue",
    });
    expect(String(chart)).toContain("<svg");
    expect(JSON.parse(String(table))).toMatchObject({
      type: "table-analysis",
      rowCount: 2,
      columnCount: 2,
    });
    expect(executable.records.map((record) => record.displayType)).toEqual(["chart", "table"]);
  });
});
