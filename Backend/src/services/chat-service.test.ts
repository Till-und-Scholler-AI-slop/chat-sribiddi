import { describe, expect, it, vi } from "vitest";
import {
  consumeGuestQuotaAtProviderStart,
  normalizedBase64Data,
  userContent,
} from "./chat-service.js";

describe("chat prompt media content", () => {
  it("converts image and file attachments to AI SDK user content parts", () => {
    const image = Buffer.from("image").toString("base64");
    const file = Buffer.from("file").toString("base64");

    const content = userContent({
      content: "please inspect these",
      media: [
        { mimeType: "image/png", data: image },
        { mimeType: "application/pdf", data: file },
      ],
    });

    expect(content).toEqual([
      { type: "text", text: "please inspect these" },
      { type: "image", image, mediaType: "image/png" },
      { type: "file", data: file, mediaType: "application/pdf" },
    ]);
  });

  it("rejects invalid base64 media payloads", () => {
    expect(() => normalizedBase64Data("not-valid-base64")).toThrow("valid base64");
  });
});

describe("guest quota provider start boundary", () => {
  it("commits only after provider_started resolves", async () => {
    let startProvider!: () => void;
    const providerStarted = new Promise<void>((resolve) => {
      startProvider = resolve;
    });
    const commit = vi.fn();

    const consuming = consumeGuestQuotaAtProviderStart(providerStarted, commit);
    expect(commit).not.toHaveBeenCalled();
    startProvider();

    await expect(consuming).resolves.toBe(true);
    expect(commit).toHaveBeenCalledOnce();
  });

  it("does not commit when the gateway fails before provider_started", async () => {
    const commit = vi.fn();
    const failure = new Error("gateway unavailable");

    await expect(consumeGuestQuotaAtProviderStart(Promise.reject(failure), commit)).rejects.toBe(
      failure,
    );
    expect(commit).not.toHaveBeenCalled();
  });
});
