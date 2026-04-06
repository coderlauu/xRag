import { expect, test } from "@playwright/test";

test("inbox to search to detail flow works", async ({ page }) => {
  const title = `E2E document ${Date.now()}`;

  await page.goto("/");
  await page.locator("#inbox-title").fill(title);
  await page.locator("#inbox-content").fill("Playwright smoke path");
  await page.locator("#inbox-tags").fill("e2e, smoke");
  await page.getByRole("button", { name: "Save note" }).click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByText(title)).toBeVisible();

  await page.getByRole("link", { name: "Back to search" }).click();
  await page.locator("#search-query").fill(title);
  await page.locator("#search-submit").click();
  await expect(page.getByRole("link", { name: title })).toBeVisible();
  await page.getByRole("link", { name: title }).click();

  await expect(page).toHaveURL(/\/detail\//);
  await expect(page.getByText(title)).toBeVisible();
});
