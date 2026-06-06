import { env } from "../config/env.js";
import type { Provider } from "../db/types.js";
import { HttpError } from "../errors/http-error.js";
import type { ApiKeyService } from "./api-key-service.js";
import type {
  ProviderConnectionService,
  ResolvedProviderConnection,
} from "./provider-connection-service.js";

export const OPENROUTER_FREE_MODEL_ID = "openrouter/free";

export type AccessLimits = {
  maxAttachments: number;
  maxAttachmentBytes: number;
  maxTotalAttachmentBytes: number;
  contextMessageLimit: number;
  parallelStreams: number;
};

export type CredentialSource =
  | "USER_KEY"
  | "USER_OAUTH"
  | "USER_DEVICE_OAUTH"
  | "USER_LOCAL_ENDPOINT"
  | "USER_SUBSCRIPTION_CLI"
  | "GUEST_OPENROUTER_FREE";

export type ResolvedCredential = {
  apiKey: string | null;
  credentialSource: CredentialSource;
  limits: AccessLimits;
  connection?: ResolvedProviderConnection | null | undefined;
};

export class CredentialAccessService {
  constructor(
    private readonly apiKeys: ApiKeyService,
    private readonly providerConnections: ProviderConnectionService,
  ) {}

  authenticatedLimits(): AccessLimits {
    return {
      maxAttachments: env.ACCESS_AUTH_MAX_ATTACHMENTS,
      maxAttachmentBytes: env.ACCESS_AUTH_MAX_ATTACHMENT_BYTES,
      maxTotalAttachmentBytes: env.ACCESS_AUTH_MAX_TOTAL_ATTACHMENT_BYTES,
      contextMessageLimit: env.ACCESS_AUTH_CONTEXT_MESSAGE_LIMIT,
      parallelStreams: env.ACCESS_AUTH_PARALLEL_STREAMS,
    };
  }

  guestLimits(): AccessLimits {
    return {
      maxAttachments: env.ACCESS_GUEST_MAX_ATTACHMENTS,
      maxAttachmentBytes: env.ACCESS_GUEST_MAX_ATTACHMENT_BYTES,
      maxTotalAttachmentBytes: env.ACCESS_GUEST_MAX_TOTAL_ATTACHMENT_BYTES,
      contextMessageLimit: env.ACCESS_GUEST_CONTEXT_MESSAGE_LIMIT,
      parallelStreams: 1,
    };
  }

  async resolveUserCredential(
    userId: string,
    provider: Provider,
    modelId: string,
    connectionId?: number | null,
  ): Promise<ResolvedCredential> {
    validateChatProvider(provider);
    validateModelId(modelId);
    const connection = await this.providerConnections.resolveReadyConnection(
      userId,
      providerId(provider),
      connectionId,
    );
    if (connection) {
      return {
        apiKey: connection.apiKey ?? null,
        credentialSource:
          connection.authMethod === "OAUTH_AUTH_CODE" || connection.authMethod === "OAUTH_PKCE"
            ? "USER_OAUTH"
            : connection.authMethod === "DEVICE_CODE"
              ? "USER_DEVICE_OAUTH"
              : connection.authMethod === "SUBSCRIPTION_CLI"
                ? "USER_SUBSCRIPTION_CLI"
                : connection.authMethod === "LOCAL_ENDPOINT"
                  ? "USER_LOCAL_ENDPOINT"
                  : "USER_KEY",
        limits: this.authenticatedLimits(),
        connection,
      };
    }
    if (connectionId != null) {
      throw new HttpError(
        403,
        "API_KEY_REQUIRED",
        "Provider connection is not available for this user",
      );
    }
    const apiKey = await this.apiKeys.getKey(userId, provider);
    if (!apiKey) {
      throw new HttpError(
        403,
        "API_KEY_REQUIRED",
        `Connect ${provider} in Settings before using this model`,
      );
    }
    return { apiKey, credentialSource: "USER_KEY", limits: this.authenticatedLimits() };
  }

  async readyUserCredential(userId: string | null, provider: Provider) {
    if (!userId || provider === "BRAVE_SEARCH") return null;
    const connection = await this.providerConnections.resolveReadyConnection(
      userId,
      providerId(provider),
    );
    if (connection) {
      return { apiKey: connection.apiKey ?? null, connection };
    }
    const apiKey = await this.apiKeys.getKey(userId, provider);
    return apiKey ? { apiKey, connection: null } : null;
  }

  resolveGuestOpenRouterFree(modelId: string): ResolvedCredential {
    validateModelId(modelId);
    if (modelId !== OPENROUTER_FREE_MODEL_ID) {
      throw new HttpError(
        403,
        "MODEL_NOT_ALLOWED",
        `Anonymous chat is restricted to ${OPENROUTER_FREE_MODEL_ID}`,
      );
    }
    if (!env.OPENROUTER_API_KEY?.trim()) {
      throw new HttpError(
        503,
        "MANAGED_PROVIDER_UNCONFIGURED",
        "OpenRouter Free is not configured on the server",
      );
    }
    return {
      apiKey: env.OPENROUTER_API_KEY.trim(),
      credentialSource: "GUEST_OPENROUTER_FREE",
      limits: this.guestLimits(),
    };
  }

  guestOpenRouterApiKey() {
    return env.OPENROUTER_API_KEY?.trim() || null;
  }
}

function validateModelId(modelId: string) {
  if (!modelId?.trim()) throw new HttpError(403, "MODEL_NOT_ALLOWED", "Model id must not be blank");
}

function validateChatProvider(provider: Provider) {
  if (provider === "BRAVE_SEARCH") {
    throw new HttpError(400, "VALIDATION_ERROR", "BRAVE_SEARCH is not a chat model provider");
  }
}

function providerId(provider: Provider) {
  switch (provider) {
    case "OPENAI":
      return "openai";
    case "GOOGLE":
      return "google";
    case "CLAUDE":
      return "anthropic";
    case "OPENROUTER":
      return "openrouter";
    case "BRAVE_SEARCH":
      return "brave-search";
  }
}
