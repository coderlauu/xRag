import { expect, test, type Page } from "@playwright/test";

const API_BASE_URL = "http://127.0.0.1:3001";
const ANSWER_STATUS_TIMEOUT_MS = 120_000;

test("search_result snapshot from /search opens /ask with the current page scope", async ({ page }) => {
  const title = `LaneF Search Snapshot ${Date.now()}`;
  const documentId = await createIndexedDocument(page, {
    title,
    content: "This document is used to verify search_result scope handoff.",
    tags: "e2e, lane-f, search"
  });

  await page.goto("/search");
  await page.getByLabel("搜索文档").fill(title);
  await page.getByRole("button", { name: "开始检索" }).click();

  await expect(page.getByRole("link", { name: title })).toBeVisible();
  await page.getByRole("link", { name: "带当前页范围去 Ask" }).click();

  await expect(page).toHaveURL(/\/ask\?/);
  await expect(page.getByLabel("作用域模式")).toHaveValue("search_result");
  await expect(page.getByLabel("搜索结果文档 ID")).toHaveValue(documentId);
  await expect(page.getByLabel("查询快照")).toHaveValue(title);
});

test("detail page document scope opens /ask with the document prefilled", async ({ page }) => {
  const title = `LaneF Detail Scope ${Date.now()}`;
  const documentId = await createIndexedDocument(page, {
    title,
    content: "This document is used to verify document scope handoff.",
    tags: "e2e, lane-f, detail"
  });

  await page.goto(`/detail/${documentId}`);
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await page.getByRole("link", { name: "在 Ask 中围绕此文档提问" }).click();

  await expect(page).toHaveURL(/\/ask\?/);
  await expect(page.getByLabel("作用域模式")).toHaveValue("document");
  await expect(page.getByLabel("文档 ID")).toHaveValue(documentId);
});

test("recent history restores a session and supports continue asking and citation jumpback", async ({ page }) => {
  const title = `LaneF History ${Date.now()}`;
  const documentId = await createIndexedDocument(page, {
    title,
    content: "This document is used to verify recent history and jumpback flows.",
    tags: "e2e, lane-f, history"
  });

  await page.goto("/ask");
  await page.getByLabel("作用域模式").selectOption("document");
  await page.getByLabel("文档 ID").fill(documentId);
  await page.getByLabel("问题").fill("第一轮问题：这份资料讲了什么？");
  await page.getByRole("button", { name: "开始问答" }).click();

  const firstSessionId = await waitForSubmittedSessionId(page);
  await waitForAnsweredSession(page, firstSessionId);
  await page.reload();

  await expect(page.getByText(firstSessionId).first()).toBeVisible();
  await expect(page.getByText("第一轮问题：这份资料讲了什么？").first()).toBeVisible();
  await expect(page.getByLabel("文档 ID")).toHaveValue(documentId);

  await page.getByRole("button", { name: "基于当前会话继续提问" }).click();
  await expect(page.getByText(`后续问题将承接会话 ${firstSessionId}`)).toBeVisible();

  await page.getByLabel("问题").fill("第二轮问题：请继续补充证据。");
  await page.getByRole("button", { name: "开始问答" }).click();

  const followUpSessionId = await waitForSubmittedSessionId(page);
  await waitForAnsweredSession(page, followUpSessionId);
  await page.reload();

  await expect(page.getByText(`继续来源：${firstSessionId}`).first()).toBeVisible();
  await expect(page.getByText("follow-up").first()).toBeVisible();

  const citationLink = page.locator(`a[href*="/detail/${documentId}"][href*="#evidence-"]`).first();
  await expect(citationLink).toBeVisible();
  await citationLink.click();

  await expect(page).toHaveURL(new RegExp(`/detail/${documentId}(\\?.*)?#evidence-`));
  await expect(page.locator("strong").filter({ hasText: "Citation Jumpback" })).toBeVisible();
  await expect(page.getByText(followUpSessionId).first()).toBeVisible();
});

async function createIndexedDocument(
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

  await waitForEvidenceChunkId(page);

  return documentId;
}

async function waitForSubmittedSessionId(page: Page) {
  const message = page.getByText(/会话 .* 已创建，正在进入 /);
  await expect(message).toBeVisible({ timeout: ANSWER_STATUS_TIMEOUT_MS });

  const text = (await message.textContent()) || "";
  const match = text.match(/会话 ([^ ]+) 已创建/);
  if (!match?.[1]) {
    throw new Error(`failed to extract session id from: ${text}`);
  }

  return match[1];
}

async function waitForAnsweredSession(page: Page, sessionId: string) {
  await expect.poll(
    async () => {
      const response = await page.request.get(`${API_BASE_URL}/api/v1/answers/${sessionId}`);
      if (!response.ok) {
        return "missing";
      }

      const payload = (await response.json()) as { status?: string };
      return payload.status || "unknown";
    },
    {
      timeout: ANSWER_STATUS_TIMEOUT_MS,
      message: `waiting for session ${sessionId} to reach answered`
    }
  ).toBe("answered");
}

async function waitForEvidenceChunkId(page: Page, previousChunkId?: string) {
  const evidenceCard = page.locator('[id^="evidence-"]').first();
  await expect(evidenceCard).toBeVisible({ timeout: ANSWER_STATUS_TIMEOUT_MS });

  if (previousChunkId) {
    await expect
      .poll(() => evidenceCard.getAttribute("id"), { timeout: ANSWER_STATUS_TIMEOUT_MS })
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
