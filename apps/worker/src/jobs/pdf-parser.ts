import { PDFParse } from "pdf-parse";

const PDF_PARSER_NAME = "pdf-parse";
const PDF_PARSER_VERSION = "2.4.5";
const PDF_PARSE_TIMEOUT_MS = 15_000;
const PDF_PARSE_MAX_BYTES = 20 * 1024 * 1024;

export interface ParsedPdfDocument {
  text: string;
  pageCount: number | null;
  parserName: string;
  parserVersion: string;
}

export async function parsePdfDocument(bytes: Uint8Array): Promise<ParsedPdfDocument> {
  if (bytes.byteLength === 0) {
    throw new Error("pdf extraction returned empty text");
  }

  if (bytes.byteLength > PDF_PARSE_MAX_BYTES) {
    throw new Error("pdf parser timeout exceeded");
  }

  const parser = new PDFParse({
    data: bytes
  });

  try {
    const [textResult, infoResult] = await Promise.all([
      withTimeout(parser.getText(), PDF_PARSE_TIMEOUT_MS, "pdf parser timeout exceeded"),
      withTimeout(parser.getInfo({ parsePageInfo: true }), PDF_PARSE_TIMEOUT_MS, "pdf parser timeout exceeded")
    ]);

    const text = textResult.text?.trim() || "";
    if (!text) {
      throw new Error("pdf extraction returned empty text");
    }

    return {
      text,
      pageCount: typeof infoResult.total === "number" ? infoResult.total : null,
      parserName: PDF_PARSER_NAME,
      parserVersion: PDF_PARSER_VERSION
    };
  } finally {
    await parser.destroy();
  }
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;

  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => {
          reject(new Error(message));
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}
