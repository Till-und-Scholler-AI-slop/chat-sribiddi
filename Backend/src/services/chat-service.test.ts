import { describe, expect, it } from "vitest";
import { normalizedBase64Data, userContent } from "./chat-service.js";

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
