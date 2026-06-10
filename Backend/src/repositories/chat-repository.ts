import crypto from "node:crypto";
import type { ExpressionBuilder } from "kysely";
import type { Db } from "../db/database.js";
import type { ChatRow, Database, MessageRow, NewMessage, Role } from "../db/types.js";
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

export const chatDto = (row: ChatRow) => ({
  chatId: row.chat_id,
  title: row.title,
  createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at,
  userId: row.user_id,
});
