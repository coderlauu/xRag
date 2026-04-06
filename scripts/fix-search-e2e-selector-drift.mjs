#!/usr/bin/env node

import fs from "node:fs";

const searchPagePath = "apps/web/src/features/search/pages/search-page.tsx";
const e2eSpecPath = "apps/web/e2e/inbox-search-detail.spec.ts";

function updateFile(path, transform) {
  const before = fs.readFileSync(path, "utf8");
  const after = transform(before);

  if (before === after) {
    return false;
  }

  fs.writeFileSync(path, after);
  return true;
}

const searchPageChanged = updateFile(searchPagePath, (source) => {
  let next = source;

  if (!next.includes('id="search-query"')) {
    next = next.replace(
      '<Input\n                aria-label="搜索文档"',
      '<Input\n                id="search-query"\n                aria-label="搜索文档"'
    );
  }

  if (!next.includes('id="search-submit"')) {
    next = next.replace(
      '<Button type="submit">开始检索</Button>',
      '<Button id="search-submit" type="submit">开始检索</Button>'
    );
  }

  return next;
});

const e2eSpecChanged = updateFile(e2eSpecPath, (source) =>
  source
    .replace('await page.getByLabel("Search documents").fill(title);', 'await page.locator("#search-query").fill(title);')
    .replace(
      'await page.getByRole("button", { name: "Search" }).click();',
      'await page.locator("#search-submit").click();'
    )
);

if (!searchPageChanged && !e2eSpecChanged) {
  console.log("Search page selector drift fix already applied.");
} else {
  console.log("Applied search page selector drift fix.");
}
