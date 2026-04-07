import assert from "node:assert/strict";
import test from "node:test";
import { parsePdfDocument } from "./pdf-parser";

const SAMPLE_PDF_CONTENT = `%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 44 >>
stream
BT
/F1 24 Tf
72 96 Td
(Hello PDF) Tj
ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f 
0000000010 00000 n 
0000000063 00000 n 
0000000122 00000 n 
0000000248 00000 n 
0000000342 00000 n 
trailer
<< /Root 1 0 R /Size 6 >>
startxref
412
%%EOF`;

test("parsePdfDocument extracts text and metadata from a simple PDF", async () => {
  const parsed = await parsePdfDocument(Buffer.from(SAMPLE_PDF_CONTENT, "utf8"));

  assert.match(parsed.text, /Hello PDF/);
  assert.equal(parsed.pageCount, 1);
  assert.equal(parsed.parserName, "pdf-parse");
  assert.equal(parsed.parserVersion, "2.4.5");
});
