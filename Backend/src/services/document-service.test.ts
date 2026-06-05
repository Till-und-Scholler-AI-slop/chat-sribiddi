import { describe, expect, it } from "vitest";
import { extractDocuments } from "./document-service.js";

describe("document extraction", () => {
  it("normalizes text documents", async () => {
    const documents = await extractDocuments(
      Buffer.from("line one\r\nline two\n", "utf8"),
      "text/plain; charset=utf-8",
    );

    expect(documents).toEqual([{ text: "line one\nline two" }]);
  });

  it("extracts text from PDF documents", async () => {
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
});

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
