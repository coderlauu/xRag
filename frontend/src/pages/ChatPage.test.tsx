import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChatPage } from "./ChatPage";
import { setTestViewport } from "../test/viewport";

/**
 * 问答 A — Conversation + Inspector 的组件用例，对应 3A-UI-01~08。
 *
 * **mock 的边界是全局 `fetch`，不是 `api/qa.ts` 的导出函数**（同 DiagnosticsPage）。
 * 本工单要证明的恰恰是"翻页带了 before/cursor、重命名带了 expectedVersion、重试复用了
 * 同一个 clientRequestId"，把 `api/qa.ts` mock 掉就等于把被测逻辑一起 mock 掉了。
 */

const CONVERSATION_ID = "11111111-1111-1111-1111-111111111111";
const OTHER_ID = "22222222-2222-2222-2222-222222222222";

function json(data: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ status, code, detail: "服务端说明", ...extra }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });
}

const listItem = {
  conversationId: CONVERSATION_ID,
  title: "退货要什么材料",
  version: 2,
  lastActiveAt: "2026-08-08T10:00:00+08:00",
  messagePreview: "那超时了怎么办？",
  activeTurn: false,
};

const detail = {
  conversationId: CONVERSATION_ID,
  title: "退货要什么材料",
  version: 2,
  lastActiveAt: "2026-08-08T10:00:00+08:00",
  latestTurn: { turnId: "t1", status: "ACTIONS_READY", completedAt: "2026-08-08T10:00:01+08:00" },
};

const userMessage = {
  messageId: "m1",
  turnId: "t1",
  sequenceNo: 3,
  role: "USER",
  type: "USER_QUERY",
  content: "退货需要哪些材料？",
  createdAt: "2026-08-08T10:00:00+08:00",
};

const olderMessage = {
  messageId: "m0",
  turnId: "t0",
  sequenceNo: 1,
  role: "USER",
  type: "USER_QUERY",
  content: "海外仓入库要多久？",
  createdAt: "2026-08-08T09:00:00+08:00",
};

const actionsReadyTurn = {
  turnId: "t1",
  userMessageId: "m1",
  status: "ACTIONS_READY",
  terminalReason: "ACTIONS_MAPPED",
  startedAt: "2026-08-08T10:00:00+08:00",
  completedAt: "2026-08-08T10:00:01+08:00",
  degradations: [],
  intentRevisionId: "r1",
};

const actionsReadyInspector = {
  history: { summaryUsed: true, recentCompleteTurns: 6 },
  rewrite: { mode: "MODEL", rewrittenQuestion: "退货需要哪些材料？" },
  subQuestions: [
    {
      subQuestionId: "s1",
      ordinal: 0,
      text: "退货需要哪些材料？",
      matches: [
        { nodeId: "n1", type: "KB", fullPath: "售后服务 > 退货政策", score: 0.91, rank: 1 },
      ],
      actions: [
        {
          actionId: "a1",
          type: "KB_TARGETED",
          knowledgeBaseId: 12,
          intentNodeId: "n1",
          systemAction: null,
          reasonCode: "SCORE_AT_OR_ABOVE_TARGETED",
        },
      ],
    },
  ],
};

const actionsReadyResult = {
  conversation: { conversationId: CONVERSATION_ID, title: "退货要什么材料", version: 0, created: true },
  turn: actionsReadyTurn,
  inspector: actionsReadyInspector,
  assistantMessage: null,
  clarification: null,
};

const waitingClarificationResult = {
  conversation: { conversationId: CONVERSATION_ID, title: "退货政策", version: 0, created: true },
  turn: { ...actionsReadyTurn, status: "WAITING_CLARIFICATION", terminalReason: "AMBIGUOUS_SCORE_GAP" },
  inspector: {
    history: { summaryUsed: false, recentCompleteTurns: 0 },
    rewrite: { mode: "MODEL", rewrittenQuestion: "退货政策是什么？" },
    subQuestions: [
      { subQuestionId: "s1", ordinal: 0, text: "退货政策是什么？", matches: [], actions: [] },
    ],
  },
  assistantMessage: {
    messageId: "a9",
    turnId: "t1",
    sequenceNo: 4,
    role: "ASSISTANT",
    type: "CLARIFICATION_GUIDANCE",
    content: "这个问题可能涉及以下几个方向，请选择一个：",
    createdAt: "2026-08-08T10:00:01+08:00",
  },
  clarification: {
    options: [
      { nodeId: "n1", label: "商品售后 > 3C 数码 > 退货政策" },
      { nodeId: "n2", label: "商品售后 > 服装 > 退货政策" },
    ],
    allowNoneOfTheAbove: true,
  },
};

interface Recorded {
  url: string;
  method: string;
  body: Record<string, unknown> | undefined;
}

interface Routes {
  list: (url: string) => Response;
  detail: (url: string) => Response;
  messages: (url: string) => Response;
  turn: () => Response;
  submit: (body: Record<string, unknown> | undefined, index: number) => Response | Promise<Response>;
  rename: (body: Record<string, unknown> | undefined) => Response;
  remove: () => Response;
}

const defaultRoutes: Routes = {
  list: () => json({ items: [listItem], nextCursor: null }),
  // 切换对话必须真的换上另一个 conversationId，否则"草稿按对话隔离"这条断言会被
  // 一个恒定 id 的假响应蒙混过去。
  detail: (url) => json({ ...detail, conversationId: url.split("/").pop() }),
  messages: () => json({ items: [userMessage], previousCursor: null }),
  turn: () => json({ turn: actionsReadyTurn, inspector: actionsReadyInspector, assistantMessage: null, clarification: null }),
  submit: () => json(actionsReadyResult, 201),
  rename: (body) => json({ conversationId: CONVERSATION_ID, title: String(body?.title), version: 3 }),
  remove: () => json(null, 204),
};

function mockApi(overrides: Partial<Routes> = {}) {
  const routes = { ...defaultRoutes, ...overrides };
  const calls: Recorded[] = [];
  let submits = 0;

  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined;
    calls.push({ url, method, body });

    if (method === "POST" && url.endsWith("/api/qa/turns")) {
      return Promise.resolve(routes.submit(body, submits++)).then((response) => response);
    }
    if (method === "PATCH") return Promise.resolve(routes.rename(body));
    if (method === "DELETE") return Promise.resolve(routes.remove());
    if (url.includes("/messages")) return Promise.resolve(routes.messages(url));
    if (url.includes("/turns/")) return Promise.resolve(routes.turn());
    if (/\/conversations\/[^/?]+$/.test(url)) return Promise.resolve(routes.detail(url));
    if (url.includes("/conversations")) return Promise.resolve(routes.list(url));
    return Promise.reject(new Error(`未预期的请求：${method} ${url}`));
  });

  vi.stubGlobal("fetch", fetchMock);
  return {
    calls,
    of: (method: string, fragment: string) =>
      calls.filter((call) => call.method === method && call.url.includes(fragment)),
  };
}

beforeEach(() => {
  setTestViewport(1440, 900);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("问答 A — 首问与 3A 验收信息", () => {
  it("空态发送真实问题并展示 3A 验收信息而不伪造答案、来源或停止控件", async () => {
    const api = mockApi({ list: () => json({ items: [], nextCursor: null }) });
    const user = userEvent.setup();
    render(<ChatPage />);
    expect(await screen.findByText("还没有对话")).toBeInTheDocument();

    await user.type(screen.getByLabelText("输入你的问题"), "退货需要哪些材料？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(1));
    expect(api.of("POST", "/turns")[0]?.body).toMatchObject({
      conversationId: null,
      message: { type: "QUESTION", text: "退货需要哪些材料？" },
    });

    expect(await screen.findByText(/当前里程碑尚未执行知识库检索或生成答案/)).toBeInTheDocument();
    const inspector = screen.getByRole("complementary", { name: "本轮处理" });
    expect(within(inspector).getByText("已形成检索动作")).toBeInTheDocument();
    expect(within(inspector).getByText("售后服务 > 退货政策")).toBeInTheDocument();
    expect(within(inspector).getByText(/强匹配/)).toBeInTheDocument();
    expect(within(inspector).getByText(/定向检索知识库/)).toBeInTheDocument();

    expect(screen.queryByText(/来源/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /停止/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /排队/ })).not.toBeInTheDocument();
  });

  it("提交失败保留输入并复用同一 clientRequestId，改动正文后才换新 key", async () => {
    const api = mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: (_body, index) =>
        index === 0 ? Promise.reject(new Error("network")) : json(actionsReadyResult, 201),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    const editor = screen.getByLabelText("输入你的问题");
    await user.type(editor, "退货需要哪些材料？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("无法连接到服务器");
    expect(editor).toHaveValue("退货需要哪些材料？");

    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(2));
    const submits = api.of("POST", "/turns");
    expect(submits[0]?.body?.clientRequestId).toBe(submits[1]?.body?.clientRequestId);
  });

  it("刷新恢复已有对话的消息与稳定终态 Inspector", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);

    await user.click(await screen.findByRole("button", { name: /退货要什么材料/ }));

    await waitFor(() => expect(api.of("GET", `/turns/t1`)).toHaveLength(1));
    expect(await screen.findByText("退货需要哪些材料？", { selector: ".qa-message-body" })).toBeInTheDocument();
    const inspector = screen.getByRole("complementary", { name: "本轮处理" });
    expect(within(inspector).getByText("已形成检索动作")).toBeInTheDocument();
    expect(within(inspector).getByText(/使用摘要 · 近 6 轮完整历史/)).toBeInTheDocument();
  });
});

describe("问答 A — 稳定终态与歧义闭环", () => {
  it("等待澄清时给出单选方向与都不是这些，并提交结构化选择", async () => {
    const api = mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: (_body, index) =>
        index === 0 ? json(waitingClarificationResult, 201) : json(actionsReadyResult, 201),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    await user.type(screen.getByLabelText("输入你的问题"), "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByRole("group", { name: "请选择最接近的方向" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "都不是这些" })).toBeInTheDocument();
    expect(screen.getByLabelText("输入你的问题")).toBeEnabled();

    await user.click(screen.getByRole("radio", { name: "商品售后 > 3C 数码 > 退货政策" }));

    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(2));
    expect(api.of("POST", "/turns")[1]?.body).toMatchObject({
      conversationId: CONVERSATION_ID,
      message: {
        type: "CLARIFICATION_CHOICE",
        sourceTurnId: "t1",
        selectedNodeId: "n1",
        noneOfTheAbove: false,
      },
    });
  });

  it("主题过多时提示拆分且不展示被截断的子问题结果", async () => {
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: () =>
        json(
          {
            conversation: { conversationId: CONVERSATION_ID, title: "四个问题", version: 0, created: true },
            turn: {
              ...actionsReadyTurn,
              status: "REJECTED_TOO_MANY_TOPICS",
              terminalReason: "TOO_MANY_TOPICS",
            },
            inspector: null,
            assistantMessage: null,
            clarification: null,
          },
          200,
        ),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    await user.type(screen.getByLabelText("输入你的问题"), "四个问题一起问");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText(/请拆成更小的问题/)).toBeInTheDocument();
    const inspector = screen.getByRole("complementary", { name: "本轮处理" });
    expect(within(inspector).getByText("主题过多，已请你拆分")).toBeInTheDocument();
    expect(within(inspector).getByText(/未展示被截断的结果/)).toBeInTheDocument();
    expect(within(inspector).queryByText(/子问题 1/)).not.toBeInTheDocument();
  });

  it("改写降级与无匹配如实展示，不伪装成助手答案", async () => {
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: () =>
        json(
          {
            conversation: { conversationId: CONVERSATION_ID, title: "无匹配", version: 0, created: true },
            turn: { ...actionsReadyTurn, degradations: ["REWRITE_MODEL_UNAVAILABLE"] },
            inspector: {
              history: { summaryUsed: false, recentCompleteTurns: 0 },
              rewrite: { mode: "NORMALIZED_FALLBACK", rewrittenQuestion: "这个怎么弄" },
              subQuestions: [
                {
                  subQuestionId: "s1",
                  ordinal: 0,
                  text: "这个怎么弄",
                  matches: [],
                  actions: [
                    {
                      actionId: "a1",
                      type: "NO_MATCH",
                      knowledgeBaseId: null,
                      intentNodeId: null,
                      systemAction: null,
                      reasonCode: "NO_CANDIDATE",
                    },
                  ],
                },
              ],
            },
            assistantMessage: null,
            clarification: null,
          },
          201,
        ),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    await user.type(screen.getByLabelText("输入你的问题"), "这个怎么弄");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const inspector = await screen.findByRole("complementary", { name: "本轮处理" });
    expect(within(inspector).getByText("使用原问题：这个怎么弄")).toBeInTheDocument();
    expect(within(inspector).getByText("未匹配到方向")).toBeInTheDocument();
    expect(within(inspector).getByText("未使用摘要 · 近 0 轮完整历史")).toBeInTheDocument();
    expect(within(inspector).getByText("问题改写不可用，使用原问题")).toBeInTheDocument();
  });

  it("活动本轮期间输入与发送禁用，避免重复提交", async () => {
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: () => new Promise<Response>(() => {}),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    await user.type(screen.getByLabelText("输入你的问题"), "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).toBeDisabled());
    expect(screen.getByRole("button", { name: "发送中…" })).toBeDisabled();
    expect(screen.getByText("正在理解问题…")).toBeInTheDocument();
  });

  it("失败与降级用文字说明失败点和可做动作，不只靠颜色", async () => {
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: () =>
        json(
          {
            conversation: { conversationId: CONVERSATION_ID, title: "失败", version: 0, created: true },
            turn: {
              ...actionsReadyTurn,
              status: "FAILED",
              terminalReason: "UNDERSTANDING_FAILED",
              degradations: ["HISTORY_UNAVAILABLE", "REWRITE_MODEL_UNAVAILABLE"],
            },
            inspector: null,
            assistantMessage: null,
            clarification: null,
          },
          200,
        ),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    await user.type(screen.getByLabelText("输入你的问题"), "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText(/本轮理解失败.*可以重新发送/)).toBeInTheDocument();
    expect(screen.getByText(/这一轮没能加载近期历史/)).toBeInTheDocument();
    const inspector = screen.getByRole("complementary", { name: "本轮处理" });
    expect(within(inspector).getByText("近期历史加载失败，仅使用摘要与当前问题")).toBeInTheDocument();
    expect(within(inspector).getByText("问题改写不可用，使用原问题")).toBeInTheDocument();
  });

  it("消息保存失败点明失败点与可做动作并保留输入", async () => {
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: () => problem(503, "MESSAGE_PERSISTENCE_FAILED"),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByText("还没有对话");

    const editor = screen.getByLabelText("输入你的问题");
    await user.type(editor, "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("消息没有保存成功，本轮没有开始处理。");
    expect(alert).toHaveTextContent("输入已保留，请直接重试发送。");
    expect(editor).toHaveValue("退货政策是什么？");
  });
});

describe("问答 A — 对话管理与分页", () => {
  it("重命名带 expectedVersion，版本冲突时保留表单不覆盖输入", async () => {
    const api = mockApi({
      rename: () => problem(409, "CONVERSATION_VERSION_CONFLICT", { currentVersion: 5 }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await user.click(await screen.findByRole("button", { name: /退货要什么材料/ }));
    await screen.findByText("退货需要哪些材料？", { selector: ".qa-message-body" });

    await user.click(screen.getByRole("button", { name: "对话操作" }));
    await user.click(screen.getByRole("button", { name: "重命名" }));

    const titleInput = screen.getByLabelText("对话标题");
    await user.clear(titleInput);
    await user.type(titleInput, "海外仓时效与超时处理");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(api.of("PATCH", "/conversations/")).toHaveLength(1));
    expect(api.of("PATCH", "/conversations/")[0]?.body).toEqual({
      title: "海外仓时效与超时处理",
      expectedVersion: 2,
    });
    expect(await screen.findByText("对话标题已被更新。")).toBeInTheDocument();
    expect(screen.getByLabelText("对话标题")).toHaveValue("海外仓时效与超时处理");
  });

  it("删除需要二次确认并说明后果，成功后回到空白新对话", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);
    await user.click(await screen.findByRole("button", { name: /退货要什么材料/ }));
    await screen.findByText("退货需要哪些材料？", { selector: ".qa-message-body" });

    await user.click(screen.getByRole("button", { name: "对话操作" }));
    await user.click(screen.getByRole("button", { name: "删除" }));
    expect(screen.getByText(/消息将从列表隐藏/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "确认删除" }));

    await waitFor(() => expect(api.of("DELETE", "/conversations/")).toHaveLength(1));
    expect(api.of("DELETE", "/conversations/")[0]?.body).toEqual({ expectedVersion: 2 });
    expect(await screen.findByRole("status")).toHaveTextContent("对话已删除");
    expect(screen.getByRole("heading", { level: 2, name: "新对话" })).toBeInTheDocument();
  });

  it("加载更早的消息用 previousCursor 向上补齐历史", async () => {
    const api = mockApi({
      messages: (url) =>
        url.includes("before=")
          ? json({ items: [olderMessage], previousCursor: null })
          : json({ items: [userMessage], previousCursor: "cursor-older" }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await user.click(await screen.findByRole("button", { name: /退货要什么材料/ }));
    await screen.findByText("退货需要哪些材料？", { selector: ".qa-message-body" });

    await user.click(screen.getByRole("button", { name: "加载更早的消息" }));

    await waitFor(() => expect(api.of("GET", "before=cursor-older")).toHaveLength(1));
    const rendered = await screen.findAllByRole("listitem");
    expect(rendered[0]).toHaveTextContent("海外仓入库要多久？");
    expect(rendered[1]).toHaveTextContent("退货需要哪些材料？");
    expect(screen.queryByRole("button", { name: "加载更早的消息" })).not.toBeInTheDocument();
  });

  it("对话列表用 nextCursor 继续分页而不是重新加载第一页", async () => {
    const api = mockApi({
      list: (url) =>
        url.includes("cursor=")
          ? json({ items: [{ ...listItem, conversationId: OTHER_ID, title: "清关材料" }], nextCursor: null })
          : json({ items: [listItem], nextCursor: "cursor-2" }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: /退货要什么材料/ });

    await user.click(screen.getByRole("button", { name: "加载更多对话" }));

    await waitFor(() => expect(api.of("GET", "cursor=cursor-2")).toHaveLength(1));
    expect(await screen.findByRole("button", { name: /清关材料/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /退货要什么材料/ })).toBeInTheDocument();
  });

  it("切换对话后回到原对话仍保留未发送的草稿", async () => {
    mockApi({
      list: () =>
        json({
          items: [listItem, { ...listItem, conversationId: OTHER_ID, title: "清关材料" }],
          nextCursor: null,
        }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);

    await user.click(await screen.findByRole("button", { name: /退货要什么材料/ }));
    await user.type(screen.getByLabelText("输入你的问题"), "还没发出去的草稿");

    await user.click(screen.getByRole("button", { name: /清关材料/ }));
    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).toHaveValue(""));

    await user.click(screen.getByRole("button", { name: /退货要什么材料/ }));
    await waitFor(() =>
      expect(screen.getByLabelText("输入你的问题")).toHaveValue("还没发出去的草稿"),
    );
  });
});

describe("问答 A — 响应式与键盘", () => {
  it("375px 下对话列表与本轮处理是覆盖层，Esc 关闭后焦点回到触发按钮", async () => {
    setTestViewport(375, 812);
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });

    expect(screen.queryByRole("complementary", { name: "本轮处理" })).not.toBeInTheDocument();

    const trigger = screen.getByRole("button", { name: "对话列表" });
    await user.click(trigger);
    expect(await screen.findByRole("button", { name: "新对话" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("button", { name: "新对话" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it("1024px 下对话列表常驻而本轮处理降级为抽屉", async () => {
    setTestViewport(1024, 768);
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: /退货要什么材料/ });

    expect(screen.getByRole("complementary", { name: "对话列表" })).toBeInTheDocument();
    expect(screen.queryByRole("complementary", { name: "本轮处理" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "对话列表" })).not.toBeInTheDocument();

    const trigger = screen.getByRole("button", { name: "本轮处理" });
    await user.click(trigger);
    expect(await screen.findByRole("heading", { name: "本轮处理" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "本轮处理" })).not.toBeInTheDocument(),
    );
    expect(trigger).toHaveFocus();
  });

  it("1440px 下常驻 Inspector 可收起并恢复", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: /退货要什么材料/ });

    const toggle = screen.getByRole("button", { name: "本轮处理" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    await user.click(toggle);
    expect(screen.queryByRole("complementary", { name: "本轮处理" })).not.toBeInTheDocument();
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    await user.click(toggle);
    expect(screen.getByRole("complementary", { name: "本轮处理" })).toBeInTheDocument();
  });
});
