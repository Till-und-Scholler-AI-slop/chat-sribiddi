import crypto from "node:crypto";
import { PDFParse } from "pdf-parse";
import { env } from "../config/env.js";
import { badRequest, notFound } from "../errors/http-error.js";
import type { ChatRepository } from "../repositories/chat-repository.js";
import type { DocumentRepository } from "../repositories/document-repository.js";
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
  constructor(
    private readonly chats: ChatRepository,
    private readonly documents: DocumentRepository,
  ) {}

  async upload(chatId: string, userId: string, file: File) {
    const chat = await this.chats.findByIdAndUser(chatId, userId);
    if (!chat) throw notFound(`Chat ${chatId} not found`);
    if (!file) throw badRequest("file is required");
    const bytes = Buffer.from(await file.arrayBuffer());
    if (bytes.byteLength === 0) throw badRequest("Document file is required");
    if (bytes.byteLength > env.CHAT_MAX_MEDIA_ATTACHMENT_BYTES) {
      throw badRequest(`Document exceeds ${env.CHAT_MAX_MEDIA_ATTACHMENT_BYTES} bytes`);
    }
    const filename = safeFilename(file.name);
    const mimeType = resolveMimeType(file.type, filename);
    if (!supportsDocument(mimeType)) throw badRequest("Unsupported document type");
    const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
    const chunks = chunkDocuments(await extractDocuments(bytes, mimeType));
    if (chunks.length === 0) throw badRequest("Document contains no extractable text");
    const document = await this.documents.createDocument({
      chatId,
      userId,
      filename,
      mimeType,
      byteSize: bytes.byteLength,
      sha256,
      status: "READY",
    });
    if (chunks.length > 0) {
      await this.documents.createChunks(
        chunks.map((chunk, i) => ({
          documentId: document.id,
          chunkIndex: i,
          text: chunk?.text ?? "",
          pageStart: chunk?.pageStart ?? null,
          pageEnd: chunk?.pageEnd ?? null,
          tokenEstimate: chunk?.tokenEstimate ?? 0,
        })),
      );
    }
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

function documentDto(row: {
  id: number;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: string;
  created_at: Date | string;
}) {
  return {
    id: row.id,
    filename: row.filename,
    mimeType: row.mime_type,
    byteSize: row.byte_size,
    status: row.status,
    createdAt: toIso(row.created_at),
  };
}
