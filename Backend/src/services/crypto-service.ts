import {
  createCipheriv,
  createDecipheriv,
  createHash,
  pbkdf2Sync,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";
import { env } from "../config/env.js";

type SecretEntry = {
  version: number;
  secret: Buffer;
};

export class CryptoService {
  private readonly active: SecretEntry;
  private readonly secrets = new Map<number, SecretEntry>();

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
    const key = springDerivedKey(this.active.secret, this.active.version);
    const cipher = createCipheriv("aes-256-gcm", key, iv);
    const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
    const tag = cipher.getAuthTag();
    return `v${this.active.version}:${Buffer.concat([iv, encrypted, tag]).toString("hex")}`;
  }

  decrypt(ciphertext: string) {
    if (!ciphertext.trim()) return ciphertext;
    const match = ciphertext.match(/^v(\d+):(.+)$/);
    if (match) {
      const version = Number.parseInt(match[1] ?? "", 10);
      const entry = this.secrets.get(version);
      if (!entry) {
        throw new Error(`No decryption key configured for version ${version}`);
      }
      return decryptSpringText(entry.secret, version, match[2] ?? "");
    }
    return decryptLegacyAesGcm(this.active.secret, ciphertext);
  }

  hmac(value: string) {
    return createHash("sha256").update(value).digest("hex");
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

function springDerivedKey(secret: Buffer, version: number) {
  const password = secret.toString("base64");
  return pbkdf2Sync(password, deriveSalt(secret, version), 1024, 32, "sha1");
}

function decryptSpringText(secret: Buffer, version: number, hexCiphertext: string) {
  const all = Buffer.from(hexCiphertext, "hex");
  if (all.length <= 32) throw new Error("Ciphertext is too short");
  const iv = all.subarray(0, 16);
  const encrypted = all.subarray(16, all.length - 16);
  const tag = all.subarray(all.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", springDerivedKey(secret, version), iv);
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
