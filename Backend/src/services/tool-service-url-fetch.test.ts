import { EventEmitter } from "node:events";
import type { RequestOptions } from "node:https";
import { PassThrough } from "node:stream";
import { beforeEach, describe, expect, it, vi } from "vitest";

const lookupMock = vi.hoisted(() => vi.fn());
const httpRequestMock = vi.hoisted(() => vi.fn());
const httpsRequestMock = vi.hoisted(() => vi.fn());

vi.mock("node:dns/promises", () => ({
  lookup: lookupMock,
}));

vi.mock("node:http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("node:http")>()),
  request: httpRequestMock,
}));

vi.mock("node:https", () => ({
  request: httpsRequestMock,
}));

import { ToolService } from "./tool-service.js";

describe("ToolService URL fetcher", () => {
  beforeEach(() => {
    lookupMock.mockReset();
    httpRequestMock.mockReset();
    httpsRequestMock.mockReset();
  });

  it("pins the resolved address while preserving the HTTPS host and SNI", async () => {
    lookupMock.mockResolvedValue([{ address: "93.184.216.34", family: 4 }]);
    httpsRequestMock.mockImplementation(mockNodeRequest(200, {}, "<h1>Fetched &amp; safe</h1>"));

    const executable = await urlFetcherTools();
    const output = await executable.tools?.["url-fetcher"]?.execute?.(
      { url: "https://example.com/docs?q=1" },
      {} as never,
    );

    expect(output).toBe("Fetched & safe");
    expect(httpsRequestMock).toHaveBeenCalledTimes(1);
    const options = httpsRequestMock.mock.calls[0]?.[0] as RequestOptions;
    expect(options.host).toBe("93.184.216.34");
    expect(options.path).toBe("/docs?q=1");
    expect(options.servername).toBe("example.com");
    expect(options.headers).toMatchObject({ Host: "example.com" });
  });

  it("revalidates redirects before following them", async () => {
    lookupMock.mockResolvedValue([{ address: "93.184.216.34", family: 4 }]);
    httpsRequestMock.mockImplementation(
      mockNodeRequest(302, { location: "http://127.0.0.1/metadata" }, ""),
    );

    const executable = await urlFetcherTools();
    const output = await executable.tools?.["url-fetcher"]?.execute?.(
      { url: "https://example.com/start" },
      {} as never,
    );

    expect(output).toContain("URL resolves to a private or reserved IP address.");
    expect(httpRequestMock).not.toHaveBeenCalled();
    expect(executable.records).toMatchObject([{ toolName: "url-fetcher", status: "ERROR" }]);
  });
});

async function urlFetcherTools() {
  const service = new ToolService(
    {
      findByIdAndUser: async () => ({ chat_id: "chat-1" }),
    } as never,
    {
      list: async () => [{ tool_name: "url-fetcher", enabled: true }],
    } as never,
  );
  return service.aiTools("chat-1", "user-1");
}

function mockNodeRequest(
  statusCode: number,
  headers: Record<string, string>,
  body: string,
) {
  return (_options: RequestOptions, callback: (response: PassThrough) => void) => {
    const request = new EventEmitter() as EventEmitter & {
      end: () => void;
      destroy: (error?: Error) => void;
    };
    request.end = vi.fn();
    request.destroy = vi.fn((error?: Error) => {
      if (error) request.emit("error", error);
    });

    const response = new PassThrough() as PassThrough & {
      statusCode: number;
      headers: Record<string, string>;
    };
    response.statusCode = statusCode;
    response.headers = headers;
    callback(response);
    queueMicrotask(() => response.end(body));
    return request;
  };
}
