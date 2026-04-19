#!/usr/bin/env node
/**
 * Production UI smoke against https://xrag.coderlau.cn
 *
 * Read-only: no mutation is performed beyond preview steps.
 * Captures screenshots, console errors, and network errors.
 *
 * Output dir: /tmp/xrag-ui-test/<UTC timestamp>/
 */
import { createRequire } from "node:module";
const require = createRequire(new URL("../apps/web/package.json", import.meta.url));
const playwrightModule = require("@playwright/test");
const { chromium } = playwrightModule;
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

const HOST = process.env.PROD_HOST || "https://xrag.coderlau.cn";
const TS = new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "Z");
const OUT_DIR = `/tmp/xrag-ui-test/${TS}`;

const consoleLogs = [];
const networkErrors = [];
const findings = [];

const passed = (label) => findings.push({ kind: "pass", label });
const note = (label) => findings.push({ kind: "note", label });
const bug = (severity, label, evidence = {}) =>
  findings.push({ kind: "bug", severity, label, evidence });

async function ensureOut() {
  await mkdir(OUT_DIR, { recursive: true });
}

async function screenshot(page, name) {
  const filePath = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: filePath, fullPage: true });
  return filePath;
}

function attach(page, label) {
  page.on("console", (msg) => {
    const t = msg.type();
    if (t === "error" || t === "warning") {
      consoleLogs.push({ context: label, type: t, text: msg.text() });
    }
  });
  page.on("pageerror", (err) => {
    consoleLogs.push({ context: label, type: "pageerror", text: err.message });
  });
  page.on("response", (r) => {
    const s = r.status();
    if (s >= 400) {
      networkErrors.push({ context: label, status: s, url: r.url() });
    }
  });
}

async function safe(label, fn) {
  try {
    await fn();
  } catch (err) {
    bug("P1", `${label}: 测试代码自身抛出异常`, { error: err.message });
  }
}

async function phase1Home(page) {
  await safe("Phase1 home", async () => {
    const t0 = Date.now();
    await page.goto(`${HOST}/`, { waitUntil: "networkidle", timeout: 30000 });
    const ms = Date.now() - t0;
    note(`首页加载 ${ms}ms`);
    await screenshot(page, "01-home-desktop");
    const title = await page.title();
    note(`首页 title='${title}'`);
    const links = await page.locator("a").allTextContents();
    note(`首页可见链接文案: ${links.slice(0, 30).join(" | ")}`);
    if (ms > 3000) bug("P2", `首页加载慢 ${ms}ms`);
    passed("首页可访问");
  });
}

async function phase2Ask(page) {
  await safe("Phase2 ask", async () => {
    await page.goto(`${HOST}/ask`, { waitUntil: "networkidle", timeout: 30000 });
    await screenshot(page, "10-ask-empty");

    // find textarea
    const textarea = page.locator("textarea").first();
    if ((await textarea.count()) === 0) {
      bug("P1", "Ask: 找不到 textarea 输入框", { url: `${HOST}/ask` });
      return;
    }

    await textarea.fill("xRag 是什么");

    // submit button: try common variants
    const submit = page
      .getByRole("button", { name: /提问|发送|ask|submit|提交|发送问题/i })
      .or(page.locator('button[type="submit"]'))
      .first();
    if ((await submit.count()) === 0) {
      bug("P1", "Ask: 找不到提交按钮", { url: `${HOST}/ask` });
      return;
    }

    const t0 = Date.now();
    await submit.click();
    note("Ask: 已提交问题，开始观察 polling");

    // wait up to 90s for either visible answer text or refusal
    const deadline = Date.now() + 90_000;
    let lastUrl = page.url();
    let terminal = false;
    let phase = "loading";
    while (Date.now() < deadline) {
      await page.waitForTimeout(2000);
      const html = await page.content();
      if (/answered|已回答|Answered|有依据/i.test(html)) {
        phase = "answered";
        terminal = true;
        break;
      }
      if (/refused|拒绝|超出范围|needs?_?scope|无法回答/i.test(html)) {
        phase = "refused";
        terminal = true;
        break;
      }
      if (/failed|失败|Internal server error/i.test(html)) {
        phase = "failed";
        terminal = true;
        break;
      }
      lastUrl = page.url();
    }
    const elapsed = Date.now() - t0;
    await screenshot(page, terminal ? `12-ask-${phase}` : "11-ask-stuck");
    if (!terminal) {
      bug(
        "P0",
        `Ask: 90s 内未到 terminal 状态（疑似 stuck polling 回归）`,
        { url: lastUrl, elapsed_ms: elapsed }
      );
    } else {
      passed(`Ask: ${elapsed}ms 进入 terminal=${phase}`);
    }
  });

  // empty input validation
  await safe("Phase2 empty validation", async () => {
    await page.goto(`${HOST}/ask`, { waitUntil: "networkidle" });
    const submit = page
      .getByRole("button", { name: /提问|发送|ask|submit|提交/i })
      .or(page.locator('button[type="submit"]'))
      .first();
    if ((await submit.count()) === 0) return;
    const disabled = await submit.isDisabled().catch(() => false);
    if (disabled) {
      passed("Ask: 空输入时按钮禁用");
    } else {
      note("Ask: 空输入时按钮可点（前端无前置校验，后端 DTO 应该兜底——见 API 探针）");
    }
  });

  // out-of-scope
  await safe("Phase2 oos", async () => {
    await page.goto(`${HOST}/ask`, { waitUntil: "networkidle" });
    const ta = page.locator("textarea").first();
    if ((await ta.count()) === 0) return;
    await ta.fill("今天天气怎么样");
    const submit = page
      .getByRole("button", { name: /提问|发送|ask|submit|提交/i })
      .or(page.locator('button[type="submit"]'))
      .first();
    if ((await submit.count()) === 0) return;
    await submit.click();
    await page.waitForTimeout(15000);
    await screenshot(page, "13-ask-oos");
    const html = await page.content();
    if (/refused|拒绝|超出范围|需要|无法回答|needs?_?scope/i.test(html)) {
      passed("Ask: 超范围问题给出 refusal/needs_scope 反馈");
    } else {
      note("Ask: 超范围问题未明显 refusal（可能仍在等待，或文案不匹配）");
    }
  });
}

async function phase3SearchDocuments(page) {
  await safe("Phase3 search", async () => {
    await page.goto(`${HOST}/search`, { waitUntil: "networkidle", timeout: 30000 });
    await screenshot(page, "20-search");
    const input = page
      .locator('input[type="search"], input[placeholder*="搜索" i], input[placeholder*="search" i]')
      .or(page.locator("input").first())
      .first();
    if ((await input.count()) === 0) {
      note("Search: 未找到搜索输入框（可能页面布局差异）");
      return;
    }
    await input.fill("xRag");
    await input.press("Enter");
    await page.waitForTimeout(2500);
    await screenshot(page, "21-search-results");
    passed("Search: 提交关键词成功");
  });
  await safe("Phase3 docs", async () => {
    await page.goto(`${HOST}/documents`, { waitUntil: "networkidle", timeout: 30000 });
    await screenshot(page, "30-doc-list");
    passed("Documents: 列表页加载");
  });
}

async function phase4Ops(page) {
  await safe("Phase4 ops", async () => {
    await page.goto(`${HOST}/ops`, { waitUntil: "networkidle", timeout: 30000 });
    await screenshot(page, "40-ops-overview");
    const text = (await page.textContent("body")) || "";
    if (/recovery|恢复/i.test(text)) {
      passed("Ops: 页面包含 recovery / 恢复 字样（v8 入口疑似存在）");
    } else {
      note("Ops: 页面未包含 recovery / 恢复 字样（可能在子页/抽屉）");
    }
    if (/sample|样本|诊断/i.test(text)) {
      passed("Ops: 页面包含 diagnostic samples / 诊断样本");
    } else {
      bug("P1", "Ops: 看不到 diagnostic samples 区块", { url: `${HOST}/ops` });
    }
    // collect h1/h2/h3
    const headings = await page.locator("h1, h2, h3").allTextContents();
    note(`Ops 标题: ${headings.slice(0, 12).join(" | ")}`);

    // try clicking first sample-like card
    const firstCard = page
      .locator("a, button")
      .filter({ hasText: /sample|样本|replay|查看|详情/i })
      .first();
    if ((await firstCard.count()) > 0) {
      await firstCard.click().catch(() => null);
      await page.waitForTimeout(2500);
      await screenshot(page, "42-sample-detail");
      const detailText = (await page.textContent("body")) || "";
      if (/recovery|恢复候选|candidate/i.test(detailText)) {
        passed("Ops 样本详情: 看到 recovery candidate 字样");
        await screenshot(page, "43-recovery-section");
      } else {
        bug("P0", "Ops 样本详情: 没有看到 v8 recovery candidate 区块", { url: page.url() });
      }
      // check for preview button
      const preview = page
        .getByRole("button", { name: /preview|预览|dry.?run/i })
        .first();
      if ((await preview.count()) > 0) {
        await preview.click().catch(() => null);
        await page.waitForTimeout(1500);
        await screenshot(page, "44-preview-panel");
        const panelText = (await page.textContent("body")) || "";
        const hasReason = /reason|原因|说明/i.test(panelText);
        const hasBlast = /blast|影响/i.test(panelText);
        const hasConfirm = await page
          .getByRole("button", { name: /confirm|确认|执行/i })
          .first()
          .count();
        if (hasReason && hasBlast && hasConfirm > 0) {
          passed("Ops preview: reason / blast / confirm 三件套齐全");
        } else {
          bug("P1", "Ops preview 面板缺字段", {
            hasReason,
            hasBlast,
            hasConfirm: hasConfirm > 0
          });
        }
        const confirmBtn = page
          .getByRole("button", { name: /confirm|确认|执行/i })
          .first();
        if ((await confirmBtn.count()) > 0) {
          const disabled = await confirmBtn.isDisabled().catch(() => false);
          if (disabled) {
            passed("Ops preview: confirm 按钮初始禁用（安全闸正确）");
          } else {
            bug("P1", "Ops preview: confirm 按钮初始未禁用", { url: page.url() });
          }
        }
        note("Ops preview: 出于安全考虑不点击 confirm");
      } else {
        note("Ops 样本详情: 未发现 preview 按钮（可能需要更深入导航）");
      }
    } else {
      note("Ops: 未发现可点开的 sample 卡片");
    }
  });
}

async function phase5Edge(page) {
  await safe("Phase5 404", async () => {
    await page.goto(`${HOST}/nonexistent-page-xyz`, { waitUntil: "networkidle", timeout: 30000 });
    await screenshot(page, "50-404");
    const text = ((await page.textContent("body")) || "").trim();
    if (/404|not found|页面不存在|没找到|找不到/i.test(text)) {
      passed("404: 前端展示了 not-found 文案");
    } else if (text.length < 20) {
      bug("P2", "404: 页面几乎为空（可能空白页）", {
        url: `${HOST}/nonexistent-page-xyz`,
        body_length: text.length
      });
    } else {
      bug("P2", "404: 没有明显 not-found 文案，可能 fallback 到首页", {
        url: page.url(),
        snippet: text.slice(0, 200)
      });
    }
  });
  await safe("Phase5 mobile", async () => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto(`${HOST}/`, { waitUntil: "networkidle" });
    await screenshot(page, "60-mobile-home");
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    if (overflow > 5) {
      bug("P2", `Mobile 首页横向溢出 ${overflow}px`);
    } else {
      passed("Mobile: 首页无横向溢出");
    }
    await page.goto(`${HOST}/ops`, { waitUntil: "networkidle" });
    await screenshot(page, "62-mobile-ops");
    const opsOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    if (opsOverflow > 5) {
      bug("P2", `Mobile /ops 横向溢出 ${opsOverflow}px`);
    } else {
      passed("Mobile: /ops 无横向溢出");
    }
  });
}

async function main() {
  await ensureOut();
  console.log(`Output dir: ${OUT_DIR}`);
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      ignoreHTTPSErrors: false
    });
    const page = await context.newPage();
    attach(page, "main");
    await phase1Home(page);
    await phase2Ask(page);
    await phase3SearchDocuments(page);
    await phase4Ops(page);
    await phase5Edge(page);
  } finally {
    await browser.close();
  }

  const summary = {
    host: HOST,
    timestamp: TS,
    out_dir: OUT_DIR,
    passed: findings.filter((f) => f.kind === "pass").map((f) => f.label),
    notes: findings.filter((f) => f.kind === "note").map((f) => f.label),
    bugs: findings.filter((f) => f.kind === "bug"),
    console_errors: consoleLogs,
    network_errors: networkErrors
  };
  await writeFile(`${OUT_DIR}/report.json`, JSON.stringify(summary, null, 2));

  // also stdout
  console.log("\n=== SUMMARY ===");
  console.log(`bugs (${summary.bugs.length}):`);
  for (const b of summary.bugs) console.log(`  [${b.severity}] ${b.label}`);
  console.log(`notes: ${summary.notes.length}, passes: ${summary.passed.length}`);
  console.log(`console errors: ${summary.console_errors.length}, network errors: ${summary.network_errors.length}`);
}

main().catch((err) => {
  console.error("FATAL:", err);
  process.exit(1);
});
