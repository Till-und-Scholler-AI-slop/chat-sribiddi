import { createCipheriv, randomBytes } from "node:crypto";
import { describe, expect, it } from "vitest";
import { CryptoService, constantTimeEqual } from "./crypto-service.js";

const secret = Buffer.alloc(32, 7).toString("base64");

describe("CryptoService", () => {
  it("round-trips versioned ciphertext", async () => {
    const crypto = new CryptoService(secret, 3);
    const encrypted = crypto.encrypt("sk-test-secret");

    expect(encrypted).toMatch(/^v3:[0-9a-f]+$/);
    expect(await crypto.decrypt(encrypted)).toBe("sk-test-secret");
    expect(crypto.activeKeyVersion()).toBe(3);
  });

  it("decrypts legacy raw AES-GCM ciphertext", async () => {
    const crypto = new CryptoService(secret, 1);
    const legacy = legacyCiphertext(Buffer.from(secret, "base64"), "legacy-secret");

    expect(await crypto.decrypt(legacy)).toBe("legacy-secret");
  });

  it("compares strings in constant time only for equal values", () => {
    expect(constantTimeEqual("abc", "abc")).toBe(true);
    expect(constantTimeEqual("abc", "abd")).toBe(false);
    expect(constantTimeEqual("abc", "abcd")).toBe(false);
  });

  it("caches the derived key across multiple decrypts", async () => {
    const crypto = new CryptoService(secret, 1);
    const encrypted = crypto.encrypt("first");
    await crypto.decrypt(encrypted);
    const cacheSize = (crypto as unknown as { keyCache: Map<number, Buffer> }).keyCache.size;
    expect(cacheSize).toBeGreaterThan(0);
    // Subsequent decrypts of the same version should hit the cache; verify
    // by checking the cache size does not grow.
    await crypto.decrypt(encrypted);
    expect((crypto as unknown as { keyCache: Map<number, Buffer> }).keyCache.size).toBe(cacheSize);
  });
});

function legacyCiphertext(key: Buffer, plaintext: string) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  return Buffer.concat([iv, encrypted, cipher.getAuthTag()]).toString("base64");
}
