import { expect, test } from "@playwright/test";

test("inbox to search to detail flow works", async ({ page }) => {
  const title = `E2E document ${Date.now()}`;

  await page.goto("/");
  await page.locator("#inbox-title").fill(title);
  await page.locator("#inbox-content").fill("Playwright smoke path");
  await page.locator("#inbox-tags").fill("e2e, smoke");
  await page.locator("#inbox-save-note").click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByText(title)).toBeVisible();

  await page.locator("#detail-back-to-search").click();
  await page.locator("#search-query").fill(title);
  await page.locator("#search-submit").click();
  await expect(page.getByRole("link", { name: title })).toBeVisible();
  await expect(page.getByText("命中说明")).toBeVisible();
  await page.getByRole("link", { name: title }).click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByText(title)).toBeVisible();
  await expect(page.getByText("处理时间线")).toBeVisible();
});

test("inbox link import entry creates a link document and opens detail", async ({ page }) => {
  await page.goto("/");
  await page.locator("#link-title").fill("Playwright 链接导入");
  await page.locator("#link-source-url").fill("https://example.com/");
  await page.locator("#link-tags").fill("e2e, link");
  await page.locator("#link-submit").click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByText("Playwright 链接导入")).toBeVisible();
  await expect(page.getByText("来源链接：https://example.com/")).toBeVisible();
});
