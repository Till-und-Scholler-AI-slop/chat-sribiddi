import {
  createCipheriv,
  createDecipheriv,
  createHash,
  pbkdf2,
  pbkdf2Sync,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";
import { promisify } from "node:util";
import { env } from "../config/env.js";

const pbkdf2Async = promisify(pbkdf2);

type SecretEntry = {
  version: number;
  secret: Buffer;
};

const PBKDF2_ITERATIONS = 1024;
const PBKDF2_KEY_LENGTH = 32;
const PBKDF2_DIGEST = "sha1";

export class CryptoService {
  private readonly active: SecretEntry;
  private readonly secrets = new Map<number, SecretEntry>();
  private readonly keyCache = new Map<number, Buffer>();
  private readonly pendingKeys = new Map<number, Promise<Buffer>>();

  constructor(
    secret = env.encryptionSecret,
    activeVersion = env.ENCRYPTION_ACTIVE_KEY_VERSION,
    decryptionSecrets = env.ENCRYPTION_DECRYPTION_SECRETS,
  ) {
    this.active = { version: activeVersion, secret: decodeSecret(secret, "ENCRYPTION_SECRET") };
    this.secrets.set(activeVersion, this.active);
    if (decryptionSecrets) {
      for (const token of decryptionSecrets.split(",")) {
        const [versionRaw, secretRaw] = token.split(":", 2);
        const version = Number.parseInt(versionRaw ?? "", 10);
        if (!Number.isInteger(version) || !secretRaw) {
          throw new Error(`Invalid ENCRYPTION_DECRYPTION_SECRETS entry: ${token}`);
        }
        if (!this.secrets.has(version)) {
          this.secrets.set(version, { version, secret: decodeSecret(secretRaw, token) });
        }
      }
    }
  }

  activeKeyVersion() {
    return this.active.version;
  }

  encrypt(plaintext: string) {
    if (!plaintext.trim()) return plaintext;
    const iv = randomBytes(16);
    const key = this.deriveKeySync(this.active.secret, this.active.version);
    const cipher = createCipheriv("aes-256-gcm", key, iv);
    const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
    const tag = cipher.getAuthTag();
    return `v${this.active.version}:${Buffer.concat([iv, encrypted, tag]).toString("hex")}`;
  }

  async decrypt(ciphertext: string) {
    if (!ciphertext.trim()) return ciphertext;
    const match = ciphertext.match(/^v(\d+):(.+)$/);
    if (match) {
      const version = Number.parseInt(match[1] ?? "", 10);
      const entry = this.secrets.get(version);
      if (!entry) {
        throw new Error(`No decryption key configured for version ${version}`);
      }
      const key = await this.deriveKey(entry.secret, version);
      return decryptSpringText(key, match[2] ?? "");
    }
    // Legacy ciphertexts used the raw ENCRYPTION_SECRET (no PBKDF2 salt).
    return decryptLegacyAesGcm(this.active.secret, ciphertext);
  }

  hmac(value: string) {
    return createHash("sha256").update(value).digest("hex");
  }

  /**
   * Derive a per-version AES key from the configured secret, caching the
   * expensive PBKDF2 result so subsequent decrypts run in O(1) instead of
   * re-deriving on every call. The synchronous path is reserved for
   * `encrypt` because it must remain synchronous; `decrypt` uses the
   * cached async derivation.
   */
  private async deriveKey(secret: Buffer, version: number): Promise<Buffer> {
    const cached = this.keyCache.get(version);
    if (cached) return cached;
    const pending = this.pendingKeys.get(version);
    if (pending) return pending;
    const promise = pbkdf2Async(
      secret.toString("base64"),
      deriveSalt(secret, version),
      PBKDF2_ITERATIONS,
      PBKDF2_KEY_LENGTH,
      PBKDF2_DIGEST,
    );
    this.pendingKeys.set(version, promise);
    try {
      const key = await promise;
      this.keyCache.set(version, key);
      return key;
    } finally {
      this.pendingKeys.delete(version);
    }
  }

  private deriveKeySync(secret: Buffer, version: number): Buffer {
    const cached = this.keyCache.get(version);
    if (cached) return cached;
    // Fall back to the synchronous derivation only if the async version has
    // never been computed. This path is hit on cold start when the first
    // request to `encrypt` arrives before any `decrypt` has warmed the cache.
    const key = pbkdf2SyncLocal(
      secret.toString("base64"),
      deriveSalt(secret, version),
      PBKDF2_ITERATIONS,
      PBKDF2_KEY_LENGTH,
      PBKDF2_DIGEST,
    );
    this.keyCache.set(version, key);
    return key;
  }
}

function decodeSecret(value: string, source: string) {
  const raw = Buffer.from(value, "base64");
  if (raw.length !== 32) {
    throw new Error(`${source} must decode to 32 bytes`);
  }
  return raw;
}

function deriveSalt(secret: Buffer, version: number) {
  const digest = createHash("sha256")
    .update(secret)
    .update(Buffer.from([version]))
    .update("chat-sribiddi-keyring", "utf8")
    .digest();
  return digest.subarray(0, 8);
}

// Re-implemented locally to keep `deriveKey` the only owner of the cache.
function pbkdf2SyncLocal(
  password: string,
  salt: Buffer,
  iterations: number,
  keylen: number,
  digest: string,
) {
  return pbkdf2Sync(password, salt, iterations, keylen, digest);
}

function decryptSpringText(key: Buffer, hexCiphertext: string) {
  const all = Buffer.from(hexCiphertext, "hex");
  if (all.length <= 32) throw new Error("Ciphertext is too short");
  const iv = all.subarray(0, 16);
  const encrypted = all.subarray(16, all.length - 16);
  const tag = all.subarray(all.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8");
}

function decryptLegacyAesGcm(secret: Buffer, ciphertext: string) {
  const all = Buffer.from(ciphertext, "base64");
  if (all.length <= 28) throw new Error("Ciphertext is too short");
  const iv = all.subarray(0, 12);
  const encrypted = all.subarray(12, all.length - 16);
  const tag = all.subarray(all.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", secret, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8");
}

export function constantTimeEqual(left: string, right: string) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}
