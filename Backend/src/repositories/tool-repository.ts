import type { Db } from "../db/database.js";

export class ChatToolRepository {
  constructor(private readonly db: Db) {}

  list(chatId: string) {
    return this.db.selectFrom("chat_tools").selectAll().where("chat_id", "=", chatId).execute();
  }

  upsert(chatId: string, toolName: string, enabled: boolean) {
    return this.db
      .insertInto("chat_tools")
      .values({ chat_id: chatId, tool_name: toolName, enabled, created_at: new Date() })
      .onConflict((oc) => oc.columns(["chat_id", "tool_name"]).doUpdateSet({ enabled }))
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  deleteByChat(chatId: string) {
    return this.db.deleteFrom("chat_tools").where("chat_id", "=", chatId).execute();
  }
}

export class ToolCallLogRepository {
  constructor(private readonly db: Db) {}

  listByMessage(messageId: number) {
    return this.db
      .selectFrom("tool_call_logs")
      .selectAll()
      .where("message_id", "=", messageId)
      .orderBy("started_at", "asc")
      .execute();
  }

  create(input: {
    messageId: number;
    clientId?: string | null;
    toolName: string;
    inputPayload: string;
    outputPayload?: string | null;
    startedAt?: Date;
    durationMs: number;
    contextExcerpt?: string | null;
    status?: string;
    errorMessage?: string | null;
    displayType?: string | null;
  }) {
    return this.db
      .insertInto("tool_call_logs")
      .values({
        message_id: input.messageId,
        tool_name: input.toolName,
        input_payload: input.inputPayload,
        output_payload: input.outputPayload ?? null,
        started_at: input.startedAt ?? new Date(),
        duration_ms: input.durationMs,
        context_excerpt: input.contextExcerpt ?? null,
        created_at: new Date(),
        client_id: input.clientId ?? null,
        status: input.status ?? "SUCCESS",
        error_message: input.errorMessage ?? null,
        display_type: input.displayType ?? null,
      })
      .returningAll()
      .executeTakeFirstOrThrow();
  }
}
