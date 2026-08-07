import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DiagnosticsPage } from "./DiagnosticsPage";
import * as fixtures from "./diagnostics/fixtures";

/**
 * Trace Console 的自动化用例，对应 test-matrix 的 UI-04/05/08/09/10。
 *
 * **mock 的边界是全局 `fetch`，不是 `api/diagnostics.ts` 的导出函数。** 这是刻意的：
 * UI-09 要证明的正是"摘要下钻发出了真实的后端查询，而不是拿当前已加载的一页近似过滤"，
 * 而 mock 掉 `fetchCalls` 就恰好把要验的那段逻辑一起 mock 掉了。在 fetch 层断言 URL，
 * 得到的证据和浏览器网络面板里看到的是同一件事。
 */

function json(data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

interface ApiBodies {
  overview?: unknown;
  calls?: unknown;
  detail?: unknown;
  readiness?: unknown;
  /** 传入后 overview 请求永不 settle，用于验证轮询不重叠。 */
  hangOverview?: boolean;
}

function mockApi(bodies: ApiBodies = {}) {
  const urls: string[] = [];
  const resolved = {
    overview: bodies.overview ?? fixtures.overview,
    calls: bodies.calls ?? fixtures.callsPage,
    detail: bodies.detail ?? fixtures.callDetail,
    readiness: bodies.readiness ?? fixtures.readiness,
  };

  const fetchMock = vi.fn((input: RequestInfo | URL): Promise<Response> => {
    const url = String(input);
    urls.push(url);
    if (url.includes("/health/ready")) return Promise.resolve(json(resolved.readiness));
    if (url.includes("/diagnostics/overview")) {
      return bodies.hangOverview
        ? new Promise<Response>(() => {})
        : Promise.resolve(json(resolved.overview));
    }
    // detail 是 /calls/{id}，列表是 /calls?...——顺序不能反。
    if (url.includes("/diagnostics/calls/")) return Promise.resolve(json(resolved.detail));
    if (url.includes("/diagnostics/calls")) return Promise.resolve(json(resolved.calls));
    return Promise.reject(new Error(`未预期的请求：${url}`));
  });

  vi.stubGlobal("fetch", fetchMock);
  return {
    urls,
    fetchMock,
    callsUrls: () => urls.filter((u) => u.includes("/diagnostics/calls") && !/\/calls\/[^?]/.test(u)),
    overviewUrls: () => urls.filter((u) => u.includes("/diagnostics/overview")),
  };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{`${location.pathname}${location.search}`}</span>;
}

function renderPage(initialUrl = "/diagnostics") {
  return render(
    <MemoryRouter initialEntries={[initialUrl]}>
      <LocationProbe />
      <Routes>
        <Route path="/diagnostics" element={<DiagnosticsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** 等首屏三个请求都落地。 */
async function waitForLoaded() {
  await screen.findByRole("group", { name: "调度总览摘要" });
  await waitFor(() => expect(screen.queryByText("正在加载最近调用…")).not.toBeInTheDocument());
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("UI-04 筛选与 URL 同步", () => {
  it("从 URL 读出筛选并带进后端请求（刷新后筛选保留）", async () => {
    const api = mockApi();
    renderPage("/diagnostics?capability=STREAM_CHAT&outcome=FAILED&outcome=STREAM_FAILED");
    await waitForLoaded();

    const [callsUrl] = api.callsUrls();
    expect(callsUrl).toContain("capability=STREAM_CHAT");
    expect(callsUrl).toContain("outcome=FAILED");
    expect(callsUrl).toContain("outcome=STREAM_FAILED");

    // 筛选控件本身也要反映 URL，否则用户看到的是"全部"却拿到了过滤结果。
    expect(screen.getByLabelText("能力")).toHaveValue("STREAM_CHAT");
  });

  it("改筛选写回 URL 并重新请求", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    await user.selectOptions(screen.getByLabelText("服务等级"), "QUALITY");

    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("tier=QUALITY"));
    await waitFor(() => {
      expect(api.callsUrls().at(-1)).toContain("tier=QUALITY");
    });
  });

  it("选中的调用详情不进 URL（不承诺深链）", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    await user.click(screen.getByRole("button", { name: /查看调用 req_internal_0001 的详情/ }));
    await screen.findByText("req_****1b7c");

    expect(screen.getByTestId("location")).not.toHaveTextContent("call-1");
  });
});

describe("UI-05 overview 30s 轮询", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("可见时每 30s 刷新 overview，且不重复拉调用列表", async () => {
    const api = mockApi();
    renderPage();
    await vi.waitFor(() => expect(api.overviewUrls()).toHaveLength(1));
    const callsBefore = api.callsUrls().length;

    await vi.advanceTimersByTimeAsync(30_000);
    await vi.waitFor(() => expect(api.overviewUrls()).toHaveLength(2));

    // 列表只在首次进入、筛选变化或手动刷新时重载，否则会在用户阅读时自己跳动。
    expect(api.callsUrls()).toHaveLength(callsBefore);
  });

  it("标签页隐藏时暂停轮询，恢复可见后继续", async () => {
    const api = mockApi();
    renderPage();
    await vi.waitFor(() => expect(api.overviewUrls()).toHaveLength(1));

    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    await vi.advanceTimersByTimeAsync(90_000);
    expect(api.overviewUrls()).toHaveLength(1);

    visibility.mockReturnValue("visible");
    await vi.advanceTimersByTimeAsync(30_000);
    await vi.waitFor(() => expect(api.overviewUrls()).toHaveLength(2));
  });

  it("上一轮未返回时跳过这一次，请求不叠加", async () => {
    const api = mockApi({ hangOverview: true });
    renderPage();
    await vi.waitFor(() => expect(api.overviewUrls()).toHaveLength(1));

    await vi.advanceTimersByTimeAsync(120_000);

    // 四个轮询周期过去，首个 overview 仍然挂着——一个都不该再发。
    expect(api.overviewUrls()).toHaveLength(1);
  });
});

describe("UI-08 隐私", () => {
  it("不渲染契约之外的字段，即使后端多返回了它们", async () => {
    mockApi({
      overview: fixtures.overviewWithLeakedFields,
      detail: fixtures.callDetailWithLeakedFields,
    });
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    await user.click(screen.getByRole("button", { name: /查看调用 req_internal_0001 的详情/ }));
    await screen.findByText("req_****1b7c");

    const text = document.body.textContent ?? "";
    for (const leaked of [
      "sk-live-must-never-render",
      "sk-deployment-must-never-render",
      "Bearer must-never-render",
      "用户的原始问题正文",
      "模型输出正文",
      "upstream said something raw",
      "req-7f3a9c22e1d84b6690ab34ff2c5e1b7c",
    ]) {
      expect(text).not.toContain(leaked);
    }
  });

  it("脱敏 Provider 请求 ID 与稳定 providerId 正常展示", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    // 稳定标识不是敏感信息，隐藏它会让诊断页失去意义。
    expect(screen.getAllByText(/aliyun-model-studio/).length).toBeGreaterThan(0);

    await user.click(screen.getByRole("button", { name: /查看调用 req_internal_0001 的详情/ }));
    expect(await screen.findByText("req_****1b7c")).toBeInTheDocument();
  });
});

describe("UI-09 摘要下钻发出真实查询", () => {
  it("“24h 失败/流中断”请求 outcome=FAILED 与 STREAM_FAILED", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    await user.click(screen.getByRole("button", { name: /24h 失败\/流中断/ }));

    await waitFor(() => {
      const url = api.callsUrls().at(-1) ?? "";
      expect(url).toContain("outcome=FAILED");
      expect(url).toContain("outcome=STREAM_FAILED");
    });
  });

  it("“24h 发生切换”请求 failoverOccurred=true", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    await user.click(screen.getByRole("button", { name: /24h 发生切换/ }));

    await waitFor(() => {
      const url = api.callsUrls().at(-1) ?? "";
      expect(url).toContain("failoverOccurred=true");
      // 两个下钻口径互斥，不能叠加成"既切换过又失败"。
      expect(url).not.toContain("outcome=");
    });
  });

  it("可用候选与 OPEN 熔断只过滤本页视图，不重新请求调用列表", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();
    const before = api.callsUrls().length;

    await user.click(screen.getByRole("button", { name: /OPEN 熔断/ }));

    // 目录里两个 deployment，只有一个 CONFIGURED；熔断表只剩 OPEN 的那条。
    expect(await screen.findByText("已熔断", { exact: false })).toBeInTheDocument();
    expect(api.callsUrls()).toHaveLength(before);
  });
});

describe("UI-10 诊断记录可能缺失", () => {
  it("两个计数大于 0 时给出提示并显示具体条数", async () => {
    mockApi({ overview: fixtures.overviewWithMissingDiagnostics });
    renderPage();
    await waitForLoaded();

    const warning = await screen.findByText(/部分调用诊断可能缺失/);
    expect(warning).toHaveTextContent("已丢弃 12 条");
    expect(warning).toHaveTextContent("写入失败 3 条");
    // 关键：这是诊断链路的问题，不是模型调用失败。
    expect(warning).toHaveTextContent("这不代表模型调用本身失败");
  });

  it("计数为 0 时不显示提示", async () => {
    mockApi();
    renderPage();
    await waitForLoaded();

    expect(screen.queryByText(/部分调用诊断可能缺失/)).not.toBeInTheDocument();
  });

  it("计数读取失败时明确说读不到，而不是当作 0", async () => {
    mockApi({
      overview: {
        ...fixtures.overview,
        summary: {
          ...fixtures.overview.summary,
          diagnosticsDroppedSinceStart: null,
          diagnosticWriteFailuresSinceStart: null,
        },
      },
    });
    renderPage();
    await waitForLoaded();

    expect(await screen.findByText(/诊断计数读取失败/)).toBeInTheDocument();
    expect(screen.queryByText(/部分调用诊断可能缺失/)).not.toBeInTheDocument();
  });
});
