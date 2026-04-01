import test from "node:test";
import assert from "node:assert/strict";
import { buildSearchText, createContentPreview, inferTextSupport, normalizeWhitespace } from "./document-utils";

test("normalizeWhitespace collapses repeated spaces and line breaks", () => {
  assert.equal(normalizeWhitespace(" a\n\nb   c "), "a b c");
});

test("createContentPreview truncates long content", () => {
  const preview = createContentPreview("x".repeat(220), 20);
  assert.equal(preview.length, 20);
  assert.equal(preview.endsWith("…"), true);
});

test("buildSearchText joins title, content, tags and file name", () => {
  const searchText = buildSearchText({
    title: "Example",
    contentClean: "Body",
    tags: ["alpha", "beta"],
    fileName: "sample.txt",
    sourceUrl: null
  });

  assert.equal(searchText.includes("Example"), true);
  assert.equal(searchText.includes("alpha beta"), true);
  assert.equal(searchText.includes("sample.txt"), true);
});

test("inferTextSupport accepts text mime types and rejects binary ones", () => {
  assert.deepEqual(inferTextSupport("text/plain"), { supported: true });
  assert.deepEqual(inferTextSupport("application/octet-stream"), {
    supported: false,
    reason: "unsupported mime type: application/octet-stream"
  });
});
