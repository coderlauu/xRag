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
