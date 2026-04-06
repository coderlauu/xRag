import assert from "node:assert/strict";
import test from "node:test";
import { mapDiagnosisCode } from "./document-processing";

test("mapDiagnosisCode returns timeout code for PDF timeout failures", () => {
  assert.equal(mapDiagnosisCode("PDF parser timeout exceeded", "application/pdf"), "pdf_parse_timeout");
});

test("mapDiagnosisCode returns empty-text code for PDF empty text failures", () => {
  assert.equal(mapDiagnosisCode("pdf extraction returned empty text", "application/pdf"), "pdf_parse_empty_text");
});

test("mapDiagnosisCode returns unsupported code for non-timeout PDF failures", () => {
  assert.equal(mapDiagnosisCode("pdf renderer crashed", "application/pdf"), "pdf_parse_unsupported");
});

test("mapDiagnosisCode returns null for non-PDF failures", () => {
  assert.equal(mapDiagnosisCode("network reset by peer", "text/plain"), null);
});
