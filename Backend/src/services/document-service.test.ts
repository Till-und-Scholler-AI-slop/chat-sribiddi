import { describe, expect, it } from "vitest";
import type { ChatRepository } from "../repositories/chat-repository.js";
import type { DocumentRepository } from "../repositories/document-repository.js";
import { DocumentService, extractDocuments } from "./document-service.js";

describe("document extraction", () => {
  it("normalizes text documents", async () => {
    const documents = await extractDocuments(
      Buffer.from("line one\r\nline two\n", "utf8"),
      "text/plain; charset=utf-8",
    );

    expect(documents).toEqual([{ text: "line one\nline two" }]);
  });

  it.skipIf(process.platform === "win32")("extracts text from PDF documents", async () => {
    const documents = await extractDocuments(minimalPdf(), "application/pdf");

    expect(documents).toHaveLength(1);
    expect(documents[0]?.page).toBe(1);
    expect(documents[0]?.text).toContain("Hono PDF extraction text");
  });

  it("rejects unsupported document types", async () => {
    await expect(
      extractDocuments(Buffer.from("data", "utf8"), "application/octet-stream"),
    ).rejects.toThrow("Unsupported document type");
  });

  it("persists uploads before processing and completes them through the durable worker", async () => {
    const repository = new FakeDocumentRepository();
    const service = new DocumentService(ownedChatRepository(), repository.asRepository());

    const uploaded = await service.upload(
      "00000000-0000-0000-0000-000000000001",
      "user-1",
      new File(["first document body"], "notes.txt", { type: "text/plain" }),
    );

    expect(uploaded.status).toBe("PROCESSING");
    expect(repository.job?.payload?.toString("utf8")).toBe("first document body");

    await service.processPendingJobs();

    expect(repository.job?.status).toBe("READY");
    expect(repository.job?.payload).toBeNull();
    expect(repository.chunks.map((chunk) => chunk.text)).toEqual(["first document body"]);
  });

  it("marks a document failed and clears its payload after three attempts", async () => {
    const repository = new FakeDocumentRepository();
    const service = new DocumentService(ownedChatRepository(), repository.asRepository());
    await service.upload(
      "00000000-0000-0000-0000-000000000001",
      "user-1",
      new File(["   "], "empty.txt", { type: "text/plain" }),
    );

    await service.processPendingJobs();

    expect(repository.job?.attempts).toBe(3);
    expect(repository.job?.status).toBe("FAILED");
    expect(repository.job?.error_code).toBe("DOCUMENT_EMPTY");
    expect(repository.job?.payload).toBeNull();
  });
});

function ownedChatRepository() {
  return {
    findByIdAndUser: async (chatId: string, userId: string) => ({
      chat_id: chatId,
      user_id: userId,
      title: null,
      created_at: new Date(),
    }),
  } as unknown as ChatRepository;
}

class FakeDocumentRepository {
  job:
    | {
        id: number;
        chat_id: string;
        user_id: string;
        filename: string;
        mime_type: string;
        byte_size: number;
        sha256: string;
        status: "PROCESSING" | "READY" | "FAILED";
        error_code: string | null;
        payload: Buffer | null;
        attempts: number;
        next_attempt_at: Date;
        claimed_at: Date | null;
        updated_at: Date;
        created_at: Date;
      }
    | undefined;
  chunks: Array<{ text: string }> = [];

  asRepository() {
    return this as unknown as DocumentRepository;
  }

  async createDocument(input: {
    chatId: string;
    userId: string;
    filename: string;
    mimeType: string;
    byteSize: number;
    sha256: string;
    payload?: Buffer | null;
  }) {
    const createdAt = new Date();
    this.job = {
      id: 1,
      chat_id: input.chatId,
      user_id: input.userId,
      filename: input.filename,
      mime_type: input.mimeType,
      byte_size: input.byteSize,
      sha256: input.sha256,
      status: "PROCESSING",
      error_code: null,
      payload: input.payload ?? null,
      attempts: 0,
      next_attempt_at: createdAt,
      claimed_at: null,
      updated_at: createdAt,
      created_at: createdAt,
    };
    return this.job;
  }

  async claimNextProcessing() {
    if (this.job?.status !== "PROCESSING" || this.job.attempts >= 3) return undefined;
    this.job.attempts += 1;
    this.job.claimed_at = new Date();
    return { ...this.job };
  }

  async completeProcessing(
    _documentId: number,
    _expectedAttempt: number,
    chunks: Array<{ text: string }>,
  ) {
    if (!this.job) return undefined;
    this.chunks = chunks;
    this.job.status = "READY";
    this.job.payload = null;
    this.job.claimed_at = null;
    return this.job;
  }

  async markProcessingFailure(_documentId: number, expectedAttempt: number, errorCode: string) {
    if (!this.job) return undefined;
    this.job.error_code = errorCode;
    this.job.claimed_at = null;
    if (expectedAttempt >= 3) {
      this.job.status = "FAILED";
      this.job.payload = null;
    }
    return this.job;
  }
}

function minimalPdf() {
  return Buffer.from(
    `%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 73 >>
stream
BT
/F1 24 Tf
100 700 Td
(Hono PDF extraction text) Tj
ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000241 00000 n 
0000000364 00000 n 
trailer
<< /Size 6 /Root 1 0 R >>
startxref
434
%%EOF
`,
    "ascii",
  );
}
