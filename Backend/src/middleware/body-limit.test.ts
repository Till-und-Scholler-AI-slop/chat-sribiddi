import { describe, expect, it } from "vitest";
import { maxRequestBytes } from "./body-limit.js";

describe("request body limits", () => {
  it("applies the public endpoint limits before body parsing", () => {
    expect(maxRequestBytes("/settings")).toBe(1 * 1024 * 1024);
    expect(maxRequestBytes("/chats/id/messages")).toBe(28 * 1024 * 1024);
    expect(maxRequestBytes("/chats/id/messages/stream")).toBe(28 * 1024 * 1024);
    expect(maxRequestBytes("/anonymous/chats/stream")).toBe(6 * 1024 * 1024);
    expect(maxRequestBytes("/chats/id/documents")).toBe(6 * 1024 * 1024);
  });
});
