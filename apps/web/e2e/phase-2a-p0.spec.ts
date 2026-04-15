import { expect, test, type Page } from "@playwright/test";

test("phase 2A web flow covers search freshness, detail evidence, ask jumpback, and ops summary", async ({ page }) => {
  const nonce = Date.now();
  const indexedTitle = `Phase2A E2E ${nonce} indexed`;

  const indexedDocId = await createTextDocument(page, {
    title: indexedTitle,
    content: "This document should be indexed and cited by the ask workspace.",
    tags: "e2e, detail, ask"
  });

  const initialEvidenceChunkId = await waitForEvidenceChunkId(page);

  await page.goto("/search");
  await page.getByLabel("搜索文档").fill(indexedTitle);
  await page.getByLabel("索引状态").selectOption("ready");
  await page.getByRole("button", { name: "开始检索" }).click();

  await expect(page).toHaveURL(/index_status=ready/);
  const indexedResultCard = page.locator("article").filter({ has: page.getByRole("link", { name: indexedTitle }) });
  await expect(indexedResultCard).toBeVisible();
  await expect(indexedResultCard.getByText(/^可引用$/)).toBeVisible();

  await page.getByRole("link", { name: indexedTitle }).click();
  await expect(page).toHaveURL(new RegExp(`/detail/${indexedDocId}`));
  await expect(page.getByRole("heading", { name: "引用证据" })).toBeVisible();

  const evidenceCard = page.locator(`[id="evidence-${initialEvidenceChunkId}"]`);
  await expect(evidenceCard).toBeVisible();
  await expect(page.getByRole("button", { name: "重建索引" })).toBeEnabled();

  await page.getByRole("button", { name: "重建索引" }).click();
  await expect(page.getByText("索引任务已提交")).toBeVisible();
  await expect(page.getByText("可跳回")).toBeVisible();
  const reindexedEvidenceChunkId = await waitForEvidenceChunkId(page, initialEvidenceChunkId);

  await page.goto("/ask");
  await page.getByLabel("问题").fill("这份资料的核心内容是什么？");
  await page.getByLabel("作用域模式").selectOption("document");
  await page.getByLabel("文档 ID").fill(indexedDocId);
  await page.getByRole("button", { name: "开始问答" }).click();

  const answerStatusCard = page.locator("article").filter({ hasText: "会话状态" }).first();
  await expect(answerStatusCard.getByText(/^已回答$/)).toBeVisible({ timeout: 120_000 });
  await expect(page.getByRole("heading", { name: "Evidence Groups" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "检索 Trace" })).toBeVisible();

  const citationLink = page.locator(`a[href*="/detail/${indexedDocId}"][href*="#evidence-"]`).first();
  await expect(citationLink).toBeVisible();
  await citationLink.click();

  await expect(page).toHaveURL(new RegExp(`#evidence-${reindexedEvidenceChunkId}$`));
  await expect(page.getByRole("heading", { name: "引用证据" })).toBeVisible();

  await page.goto("/ops");
  await expect(page.getByRole("heading", { name: "答案摘要" })).toBeVisible();
  await expect(page.getByText("可回答文档")).toBeVisible();
  await expect(page.getByText("失败文档")).toBeVisible();
  await expect(page.getByText("引用覆盖率")).toBeVisible();
});

test("inbox link import entry creates a link document and opens detail", async ({ page }) => {
  await page.goto("/");
  await page.locator("#link-title").fill("Playwright 链接导入");
  await page.locator("#link-source-url").fill("https://example.com/");
  await page.locator("#link-tags").fill("e2e, link");
  await page.locator("#link-submit").click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByRole("heading", { name: "Playwright 链接导入" })).toBeVisible();
  await expect(page.getByText("来源链接：https://example.com/")).toBeVisible();
});

async function createTextDocument(
  page: Page,
  input: {
    title: string;
    content: string;
    tags: string;
  }
) {
  await page.goto("/");
  await page.locator("#inbox-title").fill(input.title);
  await page.locator("#inbox-content").fill(input.content);
  await page.locator("#inbox-tags").fill(input.tags);
  await page.locator("#inbox-save-note").click();

  await expect(page.getByRole("heading", { name: input.title })).toBeVisible();
  const documentId = extractDocumentId(page.url());
  if (!documentId) {
    throw new Error(`failed to extract document id from ${page.url()}`);
  }

  return documentId;
}

async function waitForEvidenceChunkId(page: Page, previousChunkId?: string) {
  const evidenceCard = page.locator('[id^="evidence-"]').first();
  await expect(evidenceCard).toBeVisible({ timeout: 120_000 });

  if (previousChunkId) {
    await expect
      .poll(() => evidenceCard.getAttribute("id"), { timeout: 120_000 })
      .not.toBe(`evidence-${previousChunkId}`);
  }

  const evidenceId = await evidenceCard.getAttribute("id");
  if (!evidenceId) {
    throw new Error("missing evidence chunk id");
  }

  return evidenceId.replace(/^evidence-/, "");
}

function extractDocumentId(url: string): string | null {
  const match = url.match(/\/detail\/([^/?#]+)/);
  return match ? decodeURIComponent(match[1] || "") : null;
}
