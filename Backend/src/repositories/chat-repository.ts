import crypto from "node:crypto";
import { type ExpressionBuilder, sql } from "kysely";
import type { Db } from "../db/database.js";
import type {
  ChatRow,
  Database,
  MessageRow,
  MessageStatus,
  NewMessage,
  Provider,
  Role,
} from "../db/types.js";
import { normalizeTitle } from "../utils/strings.js";
import { now } from "../utils/time.js";

export class ChatRepository {
  constructor(private readonly db: Db) {}

  create(userId: string, title?: string | null, createdAt?: Date | string | null) {
    return this.db
      .insertInto("chats")
      .values({
        chat_id: crypto.randomUUID(),
        title: normalizeTitle(title),
        user_id: userId,
        created_at: createdAt ?? now(),
      })
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  createWithMessages(
    userId: string,
    title: string | null | undefined,
    createdAt: Date | string | null | undefined,
    messages: Array<Omit<NewMessage, "chat_id">>,
  ) {
    return this.db.transaction().execute(async (trx) => {
      const chat = await trx
        .insertInto("chats")
        .values({
          chat_id: crypto.randomUUID(),
          title: normalizeTitle(title),
          user_id: userId,
          created_at: createdAt ?? now(),
        })
        .returningAll()
        .executeTakeFirstOrThrow();
      if (messages.length > 0) {
        await trx
          .insertInto("messages")
          .values(messages.map((message) => ({ ...message, chat_id: chat.chat_id })))
          .execute();
      }
      return chat;
    });
  }

  appendUserMessage(input: {
    chatId: string;
    userId: string;
    message: string;
    context: string | null;
    titleCandidate: string;
  }) {
    return this.db.transaction().execute(async (trx) => {
      const chat = await trx
        .selectFrom("chats")
        .selectAll()
        .where("chat_id", "=", input.chatId)
        .where("user_id", "=", input.userId)
        .forUpdate()
        .executeTakeFirst();
      if (!chat) return undefined;

      const message = await trx
        .insertInto("messages")
        .values({
          chat_id: input.chatId,
          message: input.message,
          context: input.context,
          role: "USER",
          created_at: new Date(),
          prompt_tokens: null,
          generation_tokens: null,
          total_tokens: null,
          status: "COMPLETE",
          error_code: null,
        })
        .returningAll()
        .executeTakeFirstOrThrow();

      let updatedTitle: string | null = null;
      if (!chat.title || chat.title === "New chat") {
        const count = await trx
          .selectFrom("messages")
          .select((eb) => eb.fn.count<number>("id").as("count"))
          .where("chat_id", "=", input.chatId)
          .where("role", "=", "USER")
          .executeTakeFirst();
        if (Number(count?.count ?? 0) === 1) {
          updatedTitle = input.titleCandidate;
          await trx
            .updateTable("chats")
            .set({ title: updatedTitle })
            .where("chat_id", "=", input.chatId)
            .where("user_id", "=", input.userId)
            .executeTakeFirst();
        }
      }
      return {
        chat: updatedTitle ? { ...chat, title: updatedTitle } : chat,
        message,
        updatedTitle,
      };
    });
  }

  findByIdAndUser(chatId: string, userId: string) {
    return this.db
      .selectFrom("chats")
      .selectAll()
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .executeTakeFirst();
  }

  listByUser(userId: string, limit: number, offset: number) {
    return this.db
      .selectFrom("chats")
      .selectAll()
      .where("user_id", "=", userId)
      .orderBy("created_at", "desc")
      .limit(limit)
      .offset(offset)
      .execute();
  }

  updateTitle(chatId: string, userId: string, title: string) {
    return this.db
      .updateTable("chats")
      .set({ title: title.trim().slice(0, 255) })
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .returningAll()
      .executeTakeFirst();
  }

  delete(chatId: string, userId: string) {
    return this.db
      .deleteFrom("chats")
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .executeTakeFirst();
  }

  deleteByUser(userId: string) {
    return this.db.deleteFrom("chats").where("user_id", "=", userId).execute();
  }
}

export class MessageRepository {
  constructor(private readonly db: Db) {}

  create(input: NewMessage) {
    return this.db.insertInto("messages").values(input).returningAll().executeTakeFirstOrThrow();
  }

  createMany(inputs: NewMessage[]) {
    if (inputs.length === 0) return [];
    return this.db.insertInto("messages").values(inputs).returningAll().execute();
  }

  findByIdAndChat(messageId: number, chatId: string) {
    return this.db
      .selectFrom("messages")
      .selectAll()
      .where("id", "=", messageId)
      .where("chat_id", "=", chatId)
      .executeTakeFirst();
  }

  findByIdAndUser(messageId: number, userId: string) {
    return this.db
      .selectFrom("messages")
      .innerJoin("chats", "chats.chat_id", "messages.chat_id")
      .selectAll("messages")
      .where("messages.id", "=", messageId)
      .where("chats.user_id", "=", userId)
      .executeTakeFirst();
  }

  listByChat(chatId: string, limit: number, offset: number) {
    return this.db
      .selectFrom("messages")
      .selectAll()
      .where("chat_id", "=", chatId)
      .orderBy("created_at", "asc")
      .orderBy("id", "asc")
      .limit(limit)
      .offset(offset)
      .execute();
  }

  async latestByChat(chatId: string, limit: number) {
    const rows = await this.db
      .selectFrom("messages")
      .selectAll()
      .where("chat_id", "=", chatId)
      .where("status", "in", ["COMPLETE", "INCOMPLETE"])
      .orderBy("created_at", "desc")
      .orderBy("id", "desc")
      .limit(limit)
      .execute();
    return rows.reverse();
  }

  async listResponsesByChat(chatId: string, limit: number, offset: number) {
    const rows = await this.db
      .selectFrom("messages")
      .leftJoin("tool_call_logs", "tool_call_logs.message_id", "messages.id")
      .selectAll("messages")
      .select((eb: ExpressionBuilder<Database, "messages" | "tool_call_logs">) =>
        eb.fn.count<number>("tool_call_logs.id").as("tool_call_count"),
      )
      .where("messages.chat_id", "=", chatId)
      .groupBy("messages.id")
      .orderBy("messages.created_at", "asc")
      .orderBy("messages.id", "asc")
      .limit(limit)
      .offset(offset)
      .execute();
    return rows.map((row) => ({
      ...(row as MessageRow),
      hasToolCalls: Number(row.tool_call_count ?? 0) > 0,
    }));
  }

  updateMessage(messageId: number, chatId: string, message: string, context: string | null) {
    return this.db
      .updateTable("messages")
      .set({ message, context })
      .where("id", "=", messageId)
      .where("chat_id", "=", chatId)
      .returningAll()
      .executeTakeFirst();
  }

  editAndDeleteAfter(messageId: number, chatId: string, message: string, context: string | null) {
    return this.db.transaction().execute(async (trx) => {
      const updated = await trx
        .updateTable("messages")
        .set({ message, context, status: "COMPLETE", error_code: null })
        .where("id", "=", messageId)
        .where("chat_id", "=", chatId)
        .where("role", "=", "USER")
        .returningAll()
        .executeTakeFirst();
      if (!updated) return undefined;
      await trx
        .deleteFrom("messages")
        .where("chat_id", "=", chatId)
        .where("id", ">", messageId)
        .execute();
      return updated;
    });
  }

  createAssistantPlaceholder(input: { chatId: string; context: string }) {
    return this.create({
      chat_id: input.chatId,
      message: "",
      context: input.context,
      role: "ASSISTANT",
      created_at: new Date(),
      prompt_tokens: null,
      generation_tokens: null,
      total_tokens: null,
      status: "STREAMING",
      error_code: null,
    });
  }

  checkpointAssistant(messageId: number, content: string) {
    return this.db
      .updateTable("messages")
      .set({ message: content })
      .where("id", "=", messageId)
      .where("status", "=", "STREAMING")
      .executeTakeFirst();
  }

  finalizeAssistant(input: {
    messageId: number;
    chatId: string;
    content: string;
    promptTokens: number | null;
    generationTokens: number | null;
    totalTokens: number | null;
    userId: string;
    provider: Provider;
    modelId: string;
    credentialSource: string;
    toolLogs: AssistantToolLogInput[];
  }) {
    return this.db.transaction().execute(async (trx) => {
      const message = await trx
        .updateTable("messages")
        .set({
          message: input.content,
          prompt_tokens: input.promptTokens,
          generation_tokens: input.generationTokens,
          total_tokens: input.totalTokens,
          status: "COMPLETE",
          error_code: null,
        })
        .where("id", "=", input.messageId)
        .where("chat_id", "=", input.chatId)
        .where("status", "=", "STREAMING")
        .returningAll()
        .executeTakeFirstOrThrow();

      const user = await trx
        .selectFrom("users")
        .select("id")
        .where("provider_id", "=", input.userId)
        .executeTakeFirst();
      if (user) {
        await trx
          .insertInto("usage_events")
          .values({
            user_id: user.id,
            message_id: message.id,
            provider: input.provider,
            model_id: input.modelId,
            prompt_tokens: input.promptTokens,
            completion_tokens: input.generationTokens,
            total_tokens: input.totalTokens,
            cost_micro_cents: null,
            credential_source: input.credentialSource,
            created_at: new Date(),
          })
          .executeTakeFirst();
      }

      if (input.toolLogs.length > 0) {
        const createdAt = new Date();
        await trx
          .insertInto("tool_call_logs")
          .values(
            input.toolLogs.map((log) => ({
              message_id: message.id,
              client_id: log.clientId ?? null,
              tool_name: log.toolName,
              input_payload: log.inputPayload,
              output_payload: log.outputPayload ?? null,
              started_at: log.startedAt ?? createdAt,
              duration_ms: log.durationMs,
              context_excerpt: log.contextExcerpt ?? null,
              created_at: createdAt,
              status: log.status ?? "SUCCESS",
              error_message: log.errorMessage ?? null,
              display_type: log.displayType ?? null,
            })),
          )
          .execute();
      }
      return message;
    });
  }

  markAssistantFailed(messageId: number, content: string, errorCode: string) {
    const status: MessageStatus = content.trim() ? "INCOMPLETE" : "FAILED";
    return this.db
      .updateTable("messages")
      .set({ message: content, status, error_code: errorCode.slice(0, 64) })
      .where("id", "=", messageId)
      .where("status", "=", "STREAMING")
      .executeTakeFirst();
  }

  recoverOrphanedStreaming() {
    return this.db
      .updateTable("messages")
      .set({
        status: sql<MessageStatus>`CASE
          WHEN length(trim(message)) > 0 THEN 'INCOMPLETE'
          ELSE 'FAILED'
        END`,
        error_code: "PROCESS_RESTARTED",
      })
      .where("status", "=", "STREAMING")
      .executeTakeFirst();
  }

  deleteAfter(chatId: string, messageId: number) {
    return this.db
      .deleteFrom("messages")
      .where("chat_id", "=", chatId)
      .where("id", ">", messageId)
      .execute();
  }

  deleteByChat(chatId: string) {
    return this.db.deleteFrom("messages").where("chat_id", "=", chatId).execute();
  }

  deleteByUser(userId: string) {
    return this.db
      .deleteFrom("messages")
      .where(
        "chat_id",
        "in",
        this.db.selectFrom("chats").select("chat_id").where("user_id", "=", userId),
      )
      .execute();
  }

  countRole(chatId: string, role: Role) {
    return this.db
      .selectFrom("messages")
      .select((eb) => eb.fn.count<number>("id").as("count"))
      .where("chat_id", "=", chatId)
      .where("role", "=", role)
      .executeTakeFirst();
  }
}

export type AssistantToolLogInput = {
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
};

export const chatDto = (row: ChatRow) => ({
  chatId: row.chat_id,
  title: row.title,
  createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at,
  userId: row.user_id,
});
