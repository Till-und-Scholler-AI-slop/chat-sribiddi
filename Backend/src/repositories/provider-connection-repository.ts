import type { Db } from "../db/database.js";
import type { ProviderConnectionRow } from "../db/types.js";

export class ProviderConnectionRepository {
  constructor(private readonly db: Db) {}

  listByUser(userId: string) {
    return this.db
      .selectFrom("provider_connections")
      .selectAll()
      .where("user_id", "=", userId)
      .orderBy("provider_id", "asc")
      .orderBy("created_at", "asc")
      .execute();
  }

  findByIdAndUser(connectionId: number, userId: string) {
    return this.db
      .selectFrom("provider_connections")
      .selectAll()
      .where("id", "=", connectionId)
      .where("user_id", "=", userId)
      .executeTakeFirst();
  }

  findReady(
    userId: string,
    providerId: string,
    options: { connectionId?: number; authMethods?: string[] } = {},
  ) {
    let query = this.db
      .selectFrom("provider_connections")
      .selectAll()
      .where("user_id", "=", userId)
      .where("provider_id", "=", providerId)
      .where("status", "=", "READY");
    if (options.connectionId != null) {
      query = query.where("id", "=", options.connectionId);
    }
    if (options.authMethods?.length) {
      query = query.where("auth_method", "in", options.authMethods);
    }
    return query
      .orderBy("default_connection", "desc")
      .orderBy("created_at", "asc")
      .executeTakeFirst();
  }

  async hasReady(userId: string, providerId: string) {
    const row = await this.db
      .selectFrom("provider_connections")
      .select("id")
      .where("user_id", "=", userId)
      .where("provider_id", "=", providerId)
      .where("status", "=", "READY")
      .executeTakeFirst();
    return Boolean(row);
  }

  upsert(input: {
    userId: string;
    providerId: string;
    connectionProfileId: string;
    authMethod: string;
    status: string;
    displayName: string | null;
    encryptedSecretJson: string | null;
    keyVersion: number;
    defaultModelId?: string | null;
    tokenExpiresAt?: Date | null;
    scopes?: string | null;
    providerAccountId?: string | null;
    providerAccountLabel?: string | null;
  }) {
    const now = new Date();
    return this.db
      .insertInto("provider_connections")
      .values({
        user_id: input.userId,
        provider_id: input.providerId,
        connection_profile_id: input.connectionProfileId,
        auth_method: input.authMethod,
        status: input.status,
        display_name: input.displayName,
        encrypted_secret_json: input.encryptedSecretJson,
        key_version: input.keyVersion,
        default_connection: true,
        provider_account_id: input.providerAccountId ?? null,
        provider_account_label: input.providerAccountLabel ?? input.defaultModelId ?? null,
        token_expires_at: input.tokenExpiresAt ?? null,
        scopes: input.scopes ?? null,
        last_validated_at: now,
        created_at: now,
        updated_at: now,
      })
      .onConflict((oc) =>
        oc.columns(["user_id", "provider_id", "connection_profile_id"]).doUpdateSet({
          status: input.status,
          auth_method: input.authMethod,
          display_name: input.displayName,
          encrypted_secret_json: input.encryptedSecretJson,
          key_version: input.keyVersion,
          provider_account_id: input.providerAccountId ?? null,
          provider_account_label: input.providerAccountLabel ?? input.defaultModelId ?? null,
          token_expires_at: input.tokenExpiresAt ?? null,
          scopes: input.scopes ?? null,
          default_connection: true,
          last_validated_at: now,
          updated_at: now,
        }),
      )
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  updateSecret(input: {
    connectionId: number;
    encryptedSecretJson: string;
    keyVersion: number;
    tokenExpiresAt?: Date | null;
    scopes?: string | null;
  }) {
    return this.db
      .updateTable("provider_connections")
      .set({
        encrypted_secret_json: input.encryptedSecretJson,
        key_version: input.keyVersion,
        token_expires_at: input.tokenExpiresAt ?? null,
        scopes: input.scopes ?? null,
        last_validated_at: new Date(),
        updated_at: new Date(),
      })
      .where("id", "=", input.connectionId)
      .returningAll()
      .executeTakeFirst();
  }

  deleteApiKeyMethods(userId: string, providerId: string) {
    return this.db
      .deleteFrom("provider_connections")
      .where("user_id", "=", userId)
      .where("provider_id", "=", providerId)
      .where("auth_method", "in", [
        "API_KEY",
        "OAUTH_PKCE",
        "OAUTH_AUTH_CODE",
        "OPENAI_COMPATIBLE",
      ])
      .execute();
  }

  deleteConnection(userId: string, connectionId: number) {
    return this.db
      .deleteFrom("provider_connections")
      .where("user_id", "=", userId)
      .where("id", "=", connectionId)
      .executeTakeFirst();
  }

  deleteByUser(userId: string) {
    return this.db.deleteFrom("provider_connections").where("user_id", "=", userId).execute();
  }
}

export type ProviderConnectionSecret = {
  apiKey?: string;
  baseUrl?: string;
  modelId?: string;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  accessToken?: string;
  refreshToken?: string;
  tokenType?: string;
  scopes?: string[];
  clientId?: string;
  clientSecret?: string;
  quotaProjectId?: string;
  cliHomeDir?: string;
  cliSessionId?: string;
  cliBridge?: "grok-build";
};

export const connectionDefaultModelId = (row: ProviderConnectionRow) => row.provider_account_label;
