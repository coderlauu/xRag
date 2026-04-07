import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import test from "node:test";
import { OCR_TEST_MESSAGES, runPdfOcr } from "./ocr-parser";

test("runPdfOcr aggregates OCR text from rendered pages", async () => {
  const parsed = await runPdfOcr(new Uint8Array([1, 2, 3]), {
    language: "chi_sim+eng",
    timeoutMs: 1000,
    runCommand: async (command, args) => {
      if (command === "pdftoppm") {
        const prefix = args[args.length - 1];
        await writeFile(`${prefix}-1.png`, "fake-image");
        await writeFile(`${prefix}-2.png`, "fake-image");
        return {
          stdout: "",
          stderr: ""
        };
      }

      return {
        stdout: "第 1 页 OCR 文本\n",
        stderr: ""
      };
    }
  });

  assert.equal(parsed.ocrEngine, "tesseract-ocr");
  assert.equal(parsed.ocrLanguage, "chi_sim+eng");
  assert.equal(parsed.pageCount, 2);
  assert.match(parsed.text, /第 1 页 OCR 文本/);
});

test("runPdfOcr maps empty OCR output to empty-text failure", async () => {
  await assert.rejects(
    () =>
      runPdfOcr(new Uint8Array([1, 2, 3]), {
        timeoutMs: 1000,
        runCommand: async (command, args) => {
          if (command === "pdftoppm") {
            const prefix = args[args.length - 1];
            await writeFile(`${prefix}-1.png`, "fake-image");
            return {
              stdout: "",
              stderr: ""
            };
          }

          return {
            stdout: "",
            stderr: ""
          };
        }
      }),
    new RegExp(OCR_TEST_MESSAGES.empty)
  );
});

test("runPdfOcr maps command timeout failures", async () => {
  await assert.rejects(
    () =>
      runPdfOcr(new Uint8Array([1, 2, 3]), {
        timeoutMs: 1000,
        runCommand: async () => {
          throw new Error(OCR_TEST_MESSAGES.timeout);
        }
      }),
    new RegExp(OCR_TEST_MESSAGES.timeout)
  );
});
