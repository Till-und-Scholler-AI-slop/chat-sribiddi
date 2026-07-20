import crypto from "node:crypto";
import { PDFParse } from "pdf-parse";
import { env } from "../config/env.js";
import { badRequest, notFound } from "../errors/http-error.js";
import { documentJobAttempts, documentJobs } from "../observability/metrics.js";
import type { ChatRepository } from "../repositories/chat-repository.js";
import type {
  ClaimedDocumentJob,
  DocumentRepository,
} from "../repositories/document-repository.js";
import { toIso } from "../utils/time.js";

type ExtractedDocument = {
  text: string;
  page?: number | null;
};

type DocumentChunk = {
  text: string;
  pageStart: number | null;
  pageEnd: number | null;
  tokenEstimate: number;
};

export class DocumentService {
  private static readonly WORKER_COUNT = 2;
  private static readonly MAX_ATTEMPTS = 3;
  private static readonly CLAIM_LEASE_MS = 5 * 60_000;
  private static readonly IDLE_POLL_MS = 5_000;
  private processingPromise: Promise<void> | undefined;
  private wakeTimer: ReturnType<typeof setTimeout> | undefined;
  private stopping = false;

  constructor(
    private readonly chats: ChatRepository,
    private readonly documents: DocumentRepository,
  ) {
    // An unref'ed startup wake resumes jobs left PROCESSING by a previous
    // process without keeping command-line/test processes alive.
    this.scheduleProcessing(0);
  }

  async upload(chatId: string, userId: string, file: File) {
    const chat = await this.chats.findByIdAndUser(chatId, userId);
    if (!chat) throw notFound(`Chat ${chatId} not found`);
    if (!file) throw badRequest("file is required");
    const bytes = Buffer.from(await file.arrayBuffer());
    if (bytes.byteLength === 0) throw badRequest("Document file is required");
    if (bytes.byteLength > env.CHAT_MAX_DOCUMENT_BYTES) {
      throw badRequest(`Document exceeds ${env.CHAT_MAX_DOCUMENT_BYTES} bytes`);
    }
    const filename = safeFilename(file.name);
    const mimeType = resolveMimeType(file.type, filename);
    if (!supportsDocument(mimeType)) throw badRequest("Unsupported document type");
    const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
    const document = await this.documents.createDocument({
      chatId,
      userId,
      filename,
      mimeType,
      byteSize: bytes.byteLength,
      sha256,
      status: "PROCESSING",
      payload: bytes,
    });
    this.scheduleProcessing(0);
    return documentDto(document);
  }

  async list(chatId: string, userId: string) {
    const chat = await this.chats.findByIdAndUser(chatId, userId);
    if (!chat) throw notFound(`Chat ${chatId} not found`);
    return (await this.documents.list(chatId, userId)).map(documentDto);
  }

  async delete(chatId: string, userId: string, documentId: number) {
    const existing = await this.documents.find(chatId, userId, documentId);
    if (!existing) throw notFound("Document not found");
    await this.documents.delete(chatId, userId, documentId);
  }

  relevantContext(chatId: string, query: string, limit = 5) {
    return this.documents.listRelevantChunks(chatId, query, limit);
  }

  /** Drain all currently claimable jobs. Exposed for deterministic tests and
   * operational recovery; normal operation is driven by the two background
   * workers scheduled by the constructor/upload path. */
  async processPendingJobs() {
    if (this.stopping) return;
    if (!this.processingPromise) {
      this.processingPromise = this.runWorkers().finally(() => {
        this.processingPromise = undefined;
      });
    }
    return this.processingPromise;
  }

  private runWorkers() {
    return Promise.all(
      Array.from({ length: DocumentService.WORKER_COUNT }, () => this.runWorker()),
    ).then(() => undefined);
  }

  private async runWorker() {
    for (;;) {
      if (this.stopping) return;
      const staleBefore = new Date(Date.now() - DocumentService.CLAIM_LEASE_MS);
      const job = await this.documents.claimNextProcessing(staleBefore);
      if (!job) return;
      await this.processJob(job);
    }
  }

  private async processJob(job: ClaimedDocumentJob) {
    documentJobs.labels("processing").inc();
    try {
      if (!job.payload) throw new DocumentProcessingError("DOCUMENT_PAYLOAD_MISSING");
      const chunks = chunkDocuments(await extractDocuments(job.payload, job.mime_type));
      if (chunks.length === 0) throw new DocumentProcessingError("DOCUMENT_EMPTY");
      await this.documents.completeProcessing(
        job.id,
        job.attempts,
        chunks.map((chunk, index) => ({
          chunkIndex: index,
          text: chunk.text,
          pageStart: chunk.pageStart,
          pageEnd: chunk.pageEnd,
          tokenEstimate: chunk.tokenEstimate,
        })),
      );
      documentJobAttempts.labels("ready").inc();
    } catch (error) {
      const retryDelay = Math.min(30_000, 1_000 * 2 ** Math.max(0, job.attempts - 1));
      await this.documents.markProcessingFailure(
        job.id,
        Math.min(job.attempts, DocumentService.MAX_ATTEMPTS),
        documentErrorCode(error),
        new Date(Date.now() + retryDelay),
      );
      documentJobAttempts.labels("failed").inc();
    } finally {
      documentJobs.labels("processing").dec();
    }
  }

  private scheduleProcessing(delayMs: number) {
    if (this.stopping) return;
    if (this.wakeTimer) {
      if (delayMs > 0) return;
      clearTimeout(this.wakeTimer);
    }
    const timer = setTimeout(() => {
      if (this.wakeTimer === timer) this.wakeTimer = undefined;
      void this.processPendingJobs()
        .catch(() => undefined)
        .finally(() => this.scheduleProcessing(DocumentService.IDLE_POLL_MS));
    }, delayMs);
    timer.unref();
    this.wakeTimer = timer;
  }

  async shutdown() {
    this.stopping = true;
    if (this.wakeTimer) {
      clearTimeout(this.wakeTimer);
      this.wakeTimer = undefined;
    }
    await this.processingPromise;
  }
}

export async function extractDocuments(
  bytes: Buffer,
  mimeType: string,
): Promise<ExtractedDocument[]> {
  const normalized = normalizeMimeType(mimeType);
  if (normalized === "application/pdf") return extractPdf(bytes);
  if (
    normalized.startsWith("text/") ||
    ["application/json", "application/xml", "application/x-ndjson", "application/yaml"].includes(
      normalized,
    )
  ) {
    const text = normalizeText(bytes.toString("utf8"));
    return text ? [{ text }] : [];
  }
  throw badRequest("Unsupported document type");
}

function chunkDocuments(documents: ExtractedDocument[]) {
  const chunks: DocumentChunk[] = [];
  for (const document of documents) {
    for (const text of chunkText(document.text)) {
      chunks.push({
        text,
        pageStart: document.page ?? null,
        pageEnd: document.page ?? null,
        tokenEstimate: estimateTokens(text),
      });
    }
  }
  return chunks;
}

function chunkText(text: string) {
  const chunks: string[] = [];
  const normalized = normalizeText(text);
  for (let index = 0; index < normalized.length; index += 4_000) {
    const chunk = normalized.slice(index, index + 4_000).trim();
    if (chunk) chunks.push(chunk);
  }
  return chunks;
}

async function extractPdf(bytes: Buffer) {
  const parser = new PDFParse({ data: bytes });
  try {
    const result = await parser.getText();
    return result.pages
      .map((page) => ({ text: normalizePdfText(page.text), page: page.num }))
      .filter((page) => page.text.trim());
  } catch (error) {
    throw badRequest("PDF could not be read", error);
  } finally {
    await parser.destroy().catch(() => undefined);
  }
}

function supportsDocument(mimeType: string) {
  const normalized = normalizeMimeType(mimeType);
  return (
    normalized === "application/pdf" ||
    normalized.startsWith("text/") ||
    ["application/json", "application/xml", "application/x-ndjson", "application/yaml"].includes(
      normalized,
    )
  );
}

function resolveMimeType(contentType: string | undefined, filename: string) {
  if (contentType?.trim()) return normalizeMimeType(contentType);
  const lower = filename.toLowerCase();
  if (lower.endsWith(".pdf")) return "application/pdf";
  if (lower.endsWith(".json")) return "application/json";
  if (lower.endsWith(".xml")) return "application/xml";
  if (lower.endsWith(".ndjson")) return "application/x-ndjson";
  if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
  if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
  if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".csv"))
    return "text/plain";
  return "application/octet-stream";
}

function normalizeMimeType(mimeType: string | undefined) {
  if (!mimeType?.trim()) return "application/octet-stream";
  const semicolon = mimeType.indexOf(";");
  return (semicolon >= 0 ? mimeType.slice(0, semicolon) : mimeType).trim().toLowerCase();
}

function safeFilename(filename: string | undefined) {
  const value = filename?.replaceAll(/[\\/]+/g, "_").trim();
  return value || "document";
}

function normalizeText(text: string) {
  return text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").trim();
}

function normalizePdfText(text: string) {
  return normalizeText(text).replace(/[ \t\v\f]{2,}/g, " ");
}

function estimateTokens(text: string) {
  return Math.max(1, Math.ceil(text.trim().length / 4));
}

class DocumentProcessingError extends Error {
  constructor(readonly code: string) {
    super(code);
  }
}

function documentErrorCode(error: unknown) {
  if (error instanceof DocumentProcessingError) return error.code;
  if (error instanceof Error && error.message === "Unsupported document type") {
    return "DOCUMENT_TYPE_UNSUPPORTED";
  }
  return "DOCUMENT_EXTRACTION_FAILED";
}

function documentDto(row: {
  id: number;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: string;
  error_code: string | null;
  created_at: Date | string;
}) {
  return {
    id: row.id,
    filename: row.filename,
    mimeType: row.mime_type,
    byteSize: row.byte_size,
    status: row.status,
    ...(row.error_code ? { errorCode: row.error_code } : {}),
    createdAt: toIso(row.created_at),
  };
}
