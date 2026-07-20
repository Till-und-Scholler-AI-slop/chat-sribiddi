import { sql } from "kysely";
import type { Db } from "../db/database.js";
import type { ChatDocumentRow, DocumentStatus } from "../db/types.js";

export class DocumentRepository {
  constructor(private readonly db: Db) {}

  createDocument(input: {
    chatId: string;
    userId: string;
    filename: string;
    mimeType: string;
    byteSize: number;
    sha256: string;
    status: DocumentStatus;
    payload?: Buffer | null;
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
        error_code: null,
        payload: input.payload ?? null,
        attempts: 0,
        next_attempt_at: new Date(),
        claimed_at: null,
        updated_at: new Date(),
        created_at: new Date(),
      })
      .returningAll()
      .executeTakeFirstOrThrow();
  }

  claimNextProcessing(staleBefore: Date) {
    return this.db.transaction().execute(async (trx) => {
      const candidate = await trx
        .selectFrom("chat_documents")
        .select("id")
        .where("status", "=", "PROCESSING")
        .where("attempts", "<", 3)
        .where("next_attempt_at", "<=", new Date())
        .where((eb) => eb.or([eb("claimed_at", "is", null), eb("claimed_at", "<", staleBefore)]))
        .orderBy("next_attempt_at", "asc")
        .orderBy("id", "asc")
        .forUpdate()
        .skipLocked()
        .executeTakeFirst();
      if (!candidate) return undefined;

      const claimedAt = new Date();
      return trx
        .updateTable("chat_documents")
        .set({
          attempts: sql<number>`attempts + 1`,
          claimed_at: claimedAt,
          updated_at: claimedAt,
        })
        .where("id", "=", candidate.id)
        .returningAll()
        .executeTakeFirstOrThrow();
    });
  }

  completeProcessing(
    documentId: number,
    expectedAttempt: number,
    chunks: Array<{
      chunkIndex: number;
      text: string;
      tokenEstimate: number;
      pageStart?: number | null;
      pageEnd?: number | null;
    }>,
  ) {
    return this.db.transaction().execute(async (trx) => {
      const job = await trx
        .selectFrom("chat_documents")
        .select("id")
        .where("id", "=", documentId)
        .where("status", "=", "PROCESSING")
        .where("attempts", "=", expectedAttempt)
        .forUpdate()
        .executeTakeFirst();
      if (!job) return undefined;

      await trx.deleteFrom("document_chunks").where("document_id", "=", documentId).execute();
      if (chunks.length > 0) {
        await trx
          .insertInto("document_chunks")
          .values(
            chunks.map((chunk) => ({
              document_id: documentId,
              chunk_index: chunk.chunkIndex,
              text: chunk.text,
              token_estimate: chunk.tokenEstimate,
              page_start: chunk.pageStart ?? null,
              page_end: chunk.pageEnd ?? null,
            })),
          )
          .execute();
      }
      return trx
        .updateTable("chat_documents")
        .set({
          status: "READY",
          payload: null,
          error_code: null,
          claimed_at: null,
          updated_at: new Date(),
        })
        .where("id", "=", documentId)
        .returningAll()
        .executeTakeFirstOrThrow();
    });
  }

  markProcessingFailure(
    documentId: number,
    expectedAttempt: number,
    errorCode: string,
    nextAttemptAt: Date,
  ) {
    const terminal = expectedAttempt >= 3;
    return this.db
      .updateTable("chat_documents")
      .set({
        status: terminal ? "FAILED" : "PROCESSING",
        error_code: errorCode.slice(0, 64),
        claimed_at: null,
        next_attempt_at: nextAttemptAt,
        updated_at: new Date(),
        ...(terminal ? { payload: null } : {}),
      })
      .where("id", "=", documentId)
      .where("status", "=", "PROCESSING")
      .where("attempts", "=", expectedAttempt)
      .returningAll()
      .executeTakeFirst();
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

  createChunks(
    inputs: Array<{
      documentId: number;
      chunkIndex: number;
      text: string;
      tokenEstimate: number;
      pageStart?: number | null;
      pageEnd?: number | null;
    }>,
  ) {
    if (inputs.length === 0) return [];
    return this.db
      .insertInto("document_chunks")
      .values(
        inputs.map((input) => ({
          document_id: input.documentId,
          chunk_index: input.chunkIndex,
          text: input.text,
          token_estimate: input.tokenEstimate,
          page_start: input.pageStart ?? null,
          page_end: input.pageEnd ?? null,
        })),
      )
      .returningAll()
      .execute();
  }

  listRelevantChunks(chatId: string, query: string, limit: number) {
    const normalizedQuery = query.trim().slice(0, 4_000);
    const base = this.db
      .selectFrom("document_chunks")
      .innerJoin("chat_documents", "chat_documents.id", "document_chunks.document_id")
      .select([
        "document_chunks.text",
        "document_chunks.chunk_index",
        "chat_documents.filename",
        "chat_documents.id as document_id",
      ])
      .where("chat_documents.chat_id", "=", chatId)
      .where("chat_documents.status", "=", "READY");
    if (!normalizedQuery) {
      return base
        .orderBy("chat_documents.created_at", "desc")
        .orderBy("document_chunks.chunk_index", "asc")
        .limit(limit)
        .execute();
    }
    const tsQuery = sql`plainto_tsquery('simple', ${normalizedQuery})`;
    return base
      .select(sql<number>`ts_rank(document_chunks.search_vector, ${tsQuery})`.as("rank"))
      .where(sql<boolean>`document_chunks.search_vector @@ ${tsQuery}`)
      .orderBy("rank", "desc")
      .orderBy("document_chunks.id", "asc")
      .limit(limit)
      .execute();
  }
}

export type ClaimedDocumentJob = ChatDocumentRow;
