import type { Db } from "../db/database.js";
import type { Provider } from "../db/types.js";

export class UsageRepository {
  constructor(private readonly db: Db) {}

  create(input: {
    userId: number;
    messageId: number | null;
    provider: Provider;
    modelId: string;
    promptTokens: number | null;
    completionTokens: number | null;
    totalTokens: number | null;
    costMicroCents: number | null;
    credentialSource: string;
  }) {
    return this.db
      .insertInto("usage_events")
      .values({
        user_id: input.userId,
        message_id: input.messageId,
        provider: input.provider,
        model_id: input.modelId,
        prompt_tokens: input.promptTokens,
        completion_tokens: input.completionTokens,
        total_tokens: input.totalTokens,
        cost_micro_cents: input.costMicroCents,
        credential_source: input.credentialSource,
        created_at: new Date(),
      })
      .executeTakeFirst();
  }

  listForDashboard(userId: number, since: Date, until: Date, modelId?: string | null) {
    let query = this.db
      .selectFrom("usage_events")
      .selectAll()
      .where("user_id", "=", userId)
      .where("created_at", ">=", since)
      .where("created_at", "<", until);
    if (modelId) {
      query = query.where("model_id", "=", modelId);
    }
    return query.orderBy("created_at", "asc").execute();
  }
}
