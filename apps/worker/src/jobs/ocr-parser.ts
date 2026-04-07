import { mkdtemp, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { loadWorkerEnv } from "../config/env";

const OCR_ENGINE_NAME = "tesseract-ocr";
const OCR_PARSE_TIMEOUT_MESSAGE = "ocr pipeline timeout exceeded";
const OCR_EMPTY_TEXT_MESSAGE = "ocr pipeline returned empty text";

export interface ParsedOcrDocument {
  text: string;
  pageCount: number | null;
  ocrEngine: string;
  ocrLanguage: string;
}

interface CommandResult {
  stdout: string;
  stderr: string;
}

type CommandRunner = (command: string, args: string[], timeoutMs: number) => Promise<CommandResult>;

export async function runPdfOcr(
  bytes: Uint8Array,
  options: {
    language?: string;
    timeoutMs?: number;
    runCommand?: CommandRunner;
  } = {}
): Promise<ParsedOcrDocument> {
  if (bytes.byteLength === 0) {
    throw new Error(OCR_EMPTY_TEXT_MESSAGE);
  }

  const env = loadWorkerEnv();
  const language = options.language || env.ocrLanguage;
  const timeoutMs = options.timeoutMs || env.ocrTimeoutMs;
  const runCommand = options.runCommand || runCommandWithTimeout;
  const workdir = await mkdtemp(join(tmpdir(), `xrag-ocr-${randomUUID()}-`));
  const pdfPath = join(workdir, "input.pdf");
  const imagePrefix = join(workdir, "page");

  try {
    await writeFile(pdfPath, Buffer.from(bytes));
    await runCommand("pdftoppm", ["-png", pdfPath, imagePrefix], timeoutMs);

    const generated = (await readdir(workdir))
      .filter((entry) => entry.startsWith("page-") && entry.endsWith(".png"))
      .sort();

    if (generated.length === 0) {
      throw new Error("ocr runtime failed: no page images generated");
    }

    const pageTexts: string[] = [];
    for (const fileName of generated) {
      const imagePath = join(workdir, fileName);
      const result = await runCommand("tesseract", [imagePath, "stdout", "-l", language, "--psm", "6"], timeoutMs);
      const text = result.stdout.trim();
      if (text) {
        pageTexts.push(text);
      }
    }

    const fullText = pageTexts.join("\n\n").trim();
    if (!fullText) {
      throw new Error(OCR_EMPTY_TEXT_MESSAGE);
    }

    return {
      text: fullText,
      pageCount: generated.length,
      ocrEngine: OCR_ENGINE_NAME,
      ocrLanguage: language
    };
  } finally {
    await rm(workdir, { recursive: true, force: true });
  }
}

async function runCommandWithTimeout(command: string, args: string[], timeoutMs: number): Promise<CommandResult> {
  return new Promise<CommandResult>((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"]
    });

    let stdout = "";
    let stderr = "";
    let settled = false;
    let timer: NodeJS.Timeout | undefined = setTimeout(() => {
      settled = true;
      child.kill("SIGKILL");
      reject(new Error(OCR_PARSE_TIMEOUT_MESSAGE));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", (error) => {
      if (settled) {
        return;
      }

      settled = true;
      if (timer) clearTimeout(timer);
      reject(new Error(`ocr runtime failed: ${error.message}`));
    });

    child.on("close", (code) => {
      if (settled) {
        return;
      }

      settled = true;
      if (timer) clearTimeout(timer);

      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }

      reject(new Error(stderr.trim() || `ocr runtime failed with exit code ${code}`));
    });
  });
}

export const OCR_TEST_MESSAGES = {
  timeout: OCR_PARSE_TIMEOUT_MESSAGE,
  empty: OCR_EMPTY_TEXT_MESSAGE
};
