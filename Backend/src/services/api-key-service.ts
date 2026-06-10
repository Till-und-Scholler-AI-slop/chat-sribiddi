import type { Provider } from "../db/types.js";
import { badRequest } from "../errors/http-error.js";
import type { ApiKeyRepository } from "../repositories/key-repository.js";
import { hasText, legacyToProviderId } from "../utils/strings.js";
import type { CryptoService } from "./crypto-service.js";
import type { ProviderConnectionService } from "./provider-connection-service.js";

export class ApiKeyService {
  constructor(
    private readonly repository: ApiKeyRepository,
    private readonly crypto: CryptoService,
    private readonly providerConnections: ProviderConnectionService,
  ) {}

  async saveKey(userId: string, provider: Provider, apiKey: string) {
    if (!hasText(apiKey)) throw badRequest("apiKey must not be empty");
    const encrypted = this.crypto.encrypt(apiKey.trim());
    await this.repository.upsert(userId, provider, encrypted, this.crypto.activeKeyVersion());
    await this.providerConnections.saveApiKey(userId, provider, apiKey);
  }

  async getKey(userId: string, provider: Provider) {
    const connectionKey = await this.providerConnections.getApiKey(
      userId,
      legacyToProviderId(provider),
    );
    if (hasText(connectionKey)) return connectionKey;
    const row = await this.repository.find(userId, provider);
    if (!row) return null;
    const apiKey = await this.crypto.decrypt(row.encrypted_key);
    if (row.key_version < this.crypto.activeKeyVersion()) {
      await this.repository.upsert(
        userId,
        provider,
        this.crypto.encrypt(apiKey),
        this.crypto.activeKeyVersion(),
      );
    }
    return apiKey;
  }

  async hasKey(userId: string, provider: Provider) {
    return (
      (await this.providerConnections.hasReadyConnection(userId, legacyToProviderId(provider))) ||
      Boolean(await this.repository.exists(userId, provider))
    );
  }

  async deleteKey(userId: string, provider: Provider) {
    await this.repository.delete(userId, provider);
    await this.providerConnections.deleteApiKey(userId, legacyToProviderId(provider));
  }

  deleteByUser(userId: string) {
    return this.repository.deleteByUser(userId);
  }
}
