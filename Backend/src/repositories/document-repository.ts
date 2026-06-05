import type { Db } from "../db/database.js";

export class DocumentRepository {
  constructor(private readonly db: Db) {}

  createDocument(input: {
    chatId: string;
    userId: string;
    filename: string;
    mimeType: string;
    byteSize: number;
    sha256: string;
    status: string;
  }) {
    return this.db
      .insertInto("chat_documents")
      .values({
        chat_id: input.chatId,
        user_id: input.userId,
        filename: input.filename,
        mime_type: input.mimeType,
        byte_size: input.byteSize,
        sha256: input.sha256,
        status: input.status,
        created_at: new Date(),
      })
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  list(chatId: string, userId: string) {
    return this.db
      .selectFrom("chat_documents")
      .selectAll()
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .orderBy("created_at", "desc")
      .execute();
  }

  find(chatId: string, userId: string, documentId: number) {
    return this.db
      .selectFrom("chat_documents")
      .selectAll()
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .where("id", "=", documentId)
      .executeTakeFirst();
  }

  delete(chatId: string, userId: string, documentId: number) {
    return this.db
      .deleteFrom("chat_documents")
      .where("chat_id", "=", chatId)
      .where("user_id", "=", userId)
      .where("id", "=", documentId)
      .executeTakeFirst();
  }

  createChunk(input: {
    documentId: number;
    chunkIndex: number;
    text: string;
    tokenEstimate: number;
    pageStart?: number | null;
    pageEnd?: number | null;
  }) {
    return this.db
      .insertInto("document_chunks")
      .values({
        document_id: input.documentId,
        chunk_index: input.chunkIndex,
        text: input.text,
        token_estimate: input.tokenEstimate,
        page_start: input.pageStart ?? null,
        page_end: input.pageEnd ?? null,
      })
      .executeTakeFirst();
  }

  listRelevantChunks(chatId: string, query: string, limit: number) {
    const terms = query
      .split(/\s+/)
      .map((item) => item.trim())
      .filter((item) => item.length >= 3)
      .slice(0, 8);
    let builder = this.db
      .selectFrom("document_chunks")
      .innerJoin("chat_documents", "chat_documents.id", "document_chunks.document_id")
      .select([
        "document_chunks.text",
        "document_chunks.chunk_index",
        "chat_documents.filename",
        "chat_documents.id as document_id",
      ])
      .where("chat_documents.chat_id", "=", chatId)
      .where("chat_documents.status", "=", "READY")
      .limit(limit);
    if (terms.length > 0) {
      builder = builder.where((eb) =>
        eb.or(terms.map((term) => eb("document_chunks.text", "ilike", `%${term}%`))),
      );
    }
    return builder.execute();
  }
}
