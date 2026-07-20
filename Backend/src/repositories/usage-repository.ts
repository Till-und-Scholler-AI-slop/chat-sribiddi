import { sql } from "kysely";
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

  aggregateByModel(userId: number, since: Date, until: Date, modelId?: string | null) {
    let query = this.db
      .selectFrom("usage_events")
      .select([
        "provider",
        "model_id",
        sql<number>`count(*)::int`.as("calls"),
        sql<number>`coalesce(sum(greatest(0, coalesce(prompt_tokens, 0))), 0)::bigint`.as(
          "prompt_tokens",
        ),
        sql<number>`coalesce(sum(greatest(0, coalesce(completion_tokens, 0))), 0)::bigint`.as(
          "completion_tokens",
        ),
        sql<number>`coalesce(sum(greatest(0, coalesce(total_tokens, coalesce(prompt_tokens, 0) + coalesce(completion_tokens, 0)))), 0)::bigint`.as(
          "total_tokens",
        ),
        sql<number>`coalesce(sum(greatest(0, coalesce(cost_micro_cents, 0))), 0)::bigint`.as(
          "cost_micro_cents",
        ),
      ])
      .where("user_id", "=", userId)
      .where("created_at", ">=", since)
      .where("created_at", "<", until);
    if (modelId) {
      query = query.where("model_id", "=", modelId);
    }
    return query.groupBy(["provider", "model_id"]).orderBy("calls", "desc").execute();
  }

  aggregateByDay(userId: number, since: Date, until: Date, modelId?: string | null) {
    const day = sql<string>`(created_at AT TIME ZONE 'UTC')::date::text`;
    let query = this.db
      .selectFrom("usage_events")
      .select([
        day.as("day"),
        sql<number>`count(*)::int`.as("calls"),
        sql<number>`coalesce(sum(greatest(0, coalesce(total_tokens, coalesce(prompt_tokens, 0) + coalesce(completion_tokens, 0)))), 0)::bigint`.as(
          "total_tokens",
        ),
        sql<number>`coalesce(sum(greatest(0, coalesce(cost_micro_cents, 0))), 0)::bigint`.as(
          "cost_micro_cents",
        ),
      ])
      .where("user_id", "=", userId)
      .where("created_at", ">=", since)
      .where("created_at", "<", until);
    if (modelId) {
      query = query.where("model_id", "=", modelId);
    }
    return query.groupBy(day).orderBy("day", "asc").execute();
  }
}
