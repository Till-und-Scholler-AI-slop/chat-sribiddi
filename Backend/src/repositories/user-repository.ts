import type { Db } from "../db/database.js";
import type { UserRow } from "../db/types.js";

export class UserRepository {
  constructor(private readonly db: Db) {}

  findByProviderId(providerId: string) {
    return this.db
      .selectFrom("users")
      .selectAll()
      .where("provider_id", "=", providerId)
      .executeTakeFirst();
  }

  async findOrCreate(providerId: string, email?: string | null): Promise<UserRow> {
    const existing = await this.findByProviderId(providerId);
    if (existing) return existing;
    return this.db
      .insertInto("users")
      .values({ provider_id: providerId, email: email ?? null })
      .onConflict((oc) => oc.column("provider_id").doUpdateSet({ email: email ?? null }))
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  updateCustomInstructions(providerId: string, customInstructions: string | null) {
    return this.db
      .updateTable("users")
      .set({ custom_instructions: customInstructions })
      .where("provider_id", "=", providerId)
      .executeTakeFirst();
  }

  deleteByProviderId(providerId: string) {
    return this.db.deleteFrom("users").where("provider_id", "=", providerId).execute();
  }
}
