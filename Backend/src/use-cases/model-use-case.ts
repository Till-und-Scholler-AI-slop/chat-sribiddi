import type { Provider } from "../db/types.js";
import { badRequest } from "../errors/http-error.js";
import type { ApiKeyService } from "../services/api-key-service.js";
import type { CredentialAccessService } from "../services/credential-access-service.js";
import type { ModelCatalogService } from "../services/model-catalog-service.js";
import type { ProviderCatalogService } from "../services/provider-catalog-service.js";

export class ModelUseCase {
  constructor(
    private readonly catalog: ModelCatalogService,
    private readonly apiKeys: ApiKeyService,
    private readonly credentialAccess: CredentialAccessService,
    private readonly providerCatalog: ProviderCatalogService,
  ) {}

  async listByProviderId(userId: string | null, providerId: string, expert: boolean) {
    const provider = this.providerCatalog.legacyProvider(providerId);
    if (!provider || provider === "BRAVE_SEARCH") {
      throw badRequest("Provider is not executable yet");
    }
    return this.listLegacy(userId, provider, expert);
  }

  async listLegacy(userId: string | null, provider: Provider, expert: boolean) {
    if (provider === "BRAVE_SEARCH") return [];
    const userKey = userId ? await this.apiKeys.getKey(userId, provider) : null;
    const hasUserKey = Boolean(userKey?.trim());
    const apiKey =
      provider === "OPENROUTER" && !hasUserKey
        ? this.credentialAccess.guestOpenRouterApiKey()
        : userKey;
    const models = await this.catalog.listModels(provider, apiKey ?? null);
    const annotated = this.catalog.annotateModels(provider, models, hasUserKey, userId);
    return expert ? annotated : this.catalog.compactModels(provider, annotated);
  }
}
