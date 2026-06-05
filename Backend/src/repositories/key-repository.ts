import type { Db } from "../db/database.js";
import type { Provider } from "../db/types.js";

export class ApiKeyRepository {
  constructor(private readonly db: Db) {}

  find(userId: string, provider: Provider) {
    return this.db
      .selectFrom("api_keys")
      .selectAll()
      .where("user_id", "=", userId)
      .where("provider", "=", provider)
      .executeTakeFirst();
  }

  exists(userId: string, provider: Provider) {
    return this.db
      .selectFrom("api_keys")
      .select("id")
      .where("user_id", "=", userId)
      .where("provider", "=", provider)
      .executeTakeFirst();
  }

  upsert(userId: string, provider: Provider, encryptedKey: string, keyVersion: number) {
    const now = new Date();
    return this.db
      .insertInto("api_keys")
      .values({
        user_id: userId,
        provider,
        encrypted_key: encryptedKey,
        key_version: keyVersion,
        created_at: now,
        updated_at: now,
      })
      .onConflict((oc) =>
        oc.columns(["user_id", "provider"]).doUpdateSet({
          encrypted_key: encryptedKey,
          key_version: keyVersion,
          updated_at: now,
        }),
      )
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  delete(userId: string, provider: Provider) {
    return this.db
      .deleteFrom("api_keys")
      .where("user_id", "=", userId)
      .where("provider", "=", provider)
      .execute();
  }

  deleteByUser(userId: string) {
    return this.db.deleteFrom("api_keys").where("user_id", "=", userId).execute();
  }
}
