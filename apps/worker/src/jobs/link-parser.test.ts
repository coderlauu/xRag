import assert from "node:assert/strict";
import test from "node:test";
import { extractLinkContent, fetchAndExtractLinkDocument, LinkFetchError } from "./link-parser";

const SAMPLE_HTML = `
<!doctype html>
<html lang="zh-CN">
  <head>
    <title>路线图更新</title>
    <link rel="canonical" href="https://example.com/roadmap" />
  </head>
  <body>
    <article>
      <h1>路线图更新</h1>
      <p>这是第一段正文。</p>
      <p>这里还有第二段正文。</p>
    </article>
  </body>
</html>`;

test("extractLinkContent returns readable title, canonical url and body text", () => {
  const parsed = extractLinkContent(SAMPLE_HTML);

  assert.equal(parsed.title, "路线图更新");
  assert.equal(parsed.canonicalUrl, "https://example.com/roadmap");
  assert.match(parsed.text, /第一段正文/);
  assert.match(parsed.text, /第二段正文/);
});

test("fetchAndExtractLinkDocument throws blocked diagnosis for 403 responses", async () => {
  await assert.rejects(
    () =>
      fetchAndExtractLinkDocument("https://example.com/forbidden", async () =>
        new Response("forbidden", {
          status: 403,
          headers: {
            "content-type": "text/html"
          }
        })
      ),
    (error: unknown) =>
      error instanceof LinkFetchError &&
      error.diagnosisCode === "link_fetch_blocked" &&
      error.httpStatus === 403
  );
});

test("fetchAndExtractLinkDocument retries timeout-like failures and eventually succeeds", async () => {
  let attempt = 0;

  const parsed = await fetchAndExtractLinkDocument(
    "https://example.com/retry",
    async () => {
      attempt += 1;
      if (attempt < 3) {
        throw new Error("fetch failed");
      }

      return new Response(SAMPLE_HTML, {
        status: 200,
        headers: {
          "content-type": "text/html; charset=utf-8"
        }
      });
    },
    {
      timeoutMs: 1000,
      retryCount: 2,
      retryBackoffMs: 0
    }
  );

  assert.equal(attempt, 3);
  assert.equal(parsed.title, "路线图更新");
  assert.match(parsed.text, /第一段正文/);
});

test("fetchAndExtractLinkDocument exposes readable network error summary", async () => {
  await assert.rejects(
    () =>
      fetchAndExtractLinkDocument(
        "https://example.com/broken",
        async () => {
          throw new Error("fetch failed");
        },
        {
          timeoutMs: 1000,
          retryCount: 0
        }
      ),
    (error: unknown) =>
      error instanceof LinkFetchError &&
      error.diagnosisCode === "link_fetch_timeout" &&
      error.message.includes("Link fetch network error")
  );
});
