import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { ChatPage } from "./ChatPage";
import { setTestViewport } from "../test/viewport";

/**
 * 问答 C — Focused Chat 的组件用例，对应 3A-UI-01~08（2026-08-08 随布局改判从 A 迁来）。
 *
 * **mock 的边界是全局 `fetch`，不是 `api/qa.ts` 的导出函数**（同 DiagnosticsPage）。
 * 本工单要证明的恰恰是"翻页带了 before/cursor、重命名带了 expectedVersion、重试复用了
 * 同一个 clientRequestId"，把 `api/qa.ts` mock 掉就等于把被测逻辑一起 mock 掉了。
 *
 * 布局相关的断言只剩一条纪律：**本轮处理是抽屉，任何时候都得先点开**。用 `openInspector`
 * 统一这一步，别在用例里各写各的。
 */

type TestUser = ReturnType<typeof userEvent.setup>;

/** 点开「对话列表」抽屉并返回它。C 布局下它也没有常驻形态。 */
async function openList(user: TestUser) {
  await user.click(screen.getByRole("button", { name: "对话列表" }));
  return screen.findByRole("dialog", { name: "对话列表" });
}

/** 打开对话列表抽屉并取出其中一项，供点击。 */
async function openListItem(user: TestUser, name: RegExp) {
  const drawer = await openList(user);
  return within(drawer).findByRole("button", { name });
}

/** 点开「本轮处理」抽屉并返回它。C 布局下它没有常驻形态（ui-spec §2.2）。 */
async function openInspector(user: TestUser) {
  await user.click(screen.getByRole("button", { name: "本轮处理" }));
  return screen.findByRole("dialog", { name: "本轮处理" });
}

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

/**
 * 3B 检索块（api.md §9）。**没有任何分数字段**——fixture 里就不该有，否则"界面不出现分数"
 * 这条断言会因为数据里没有分数而假绿。
 */
const retrievedBlock = {
  status: "RETRIEVED",
  partial: false,
  rerankApplied: true,
  degradation: null,
  sources: [
    {
      ordinal: 0,
      chunkId: 88123,
      knowledgeBaseId: 12,
      knowledgeBaseName: "售后服务知识库",
      documentId: 517,
      documentName: "退货与退款操作手册 v3.docx",
      chunkIndex: 12,
      documentDeleted: false,
    },
    {
      ordinal: 1,
      chunkId: 88124,
      knowledgeBaseId: 12,
      knowledgeBaseName: "售后服务知识库",
      documentId: 518,
      documentName: null,
      chunkIndex: 4,
      documentDeleted: true,
    },
  ],
  failedTargets: [],
};

function resultWith(retrieval: unknown) {
  return { ...actionsReadyResult, retrieval, tools: [] };
}

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
  retryRetrieval: (body: Record<string, unknown> | undefined) => Response;
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
  retryRetrieval: () => json(resultWith({ ...retrievedBlock, partial: false, failedTargets: [] })),
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

    if (method === "POST" && url.includes("/retrieval/retry")) {
      return Promise.resolve(routes.retryRetrieval(body));
    }
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

describe("问答 C — 首问与 3A 验收信息", () => {
  it("空态发送真实问题并展示 3A 验收信息而不伪造答案、来源或停止控件", async () => {
    const api = mockApi({ list: () => json({ items: [], nextCursor: null }) });
    const user = userEvent.setup();
    render(<ChatPage />);
    const list = await openList(user);
    expect(within(list).getByText("还没有对话")).toBeInTheDocument();
    await user.keyboard("{Escape}");

    await user.type(screen.getByLabelText("输入你的问题"), "退货需要哪些材料？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(1));
    expect(api.of("POST", "/turns")[0]?.body).toMatchObject({
      conversationId: null,
      message: { type: "QUESTION", text: "退货需要哪些材料？" },
    });

    expect(await screen.findByText(/当前里程碑尚未执行知识库检索或生成答案/)).toBeInTheDocument();
    const inspector = await openInspector(user);
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
    await screen.findByRole("button", { name: "对话列表" });

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

    await user.click(await openListItem(user, /退货要什么材料/));

    await waitFor(() => expect(api.of("GET", `/turns/t1`)).toHaveLength(1));
    expect(await screen.findByText("退货需要哪些材料？", { selector: ".qa-message-body" })).toBeInTheDocument();
    const inspector = await openInspector(user);
    expect(within(inspector).getByText("已形成检索动作")).toBeInTheDocument();
    expect(within(inspector).getByText(/使用摘要 · 近 6 轮完整历史/)).toBeInTheDocument();
  });
});

describe("问答 C — 稳定终态与歧义闭环", () => {
  it("等待澄清时给出单选方向与都不是这些，并提交结构化选择", async () => {
    const api = mockApi({
      list: () => json({ items: [], nextCursor: null }),
      submit: (_body, index) =>
        index === 0 ? json(waitingClarificationResult, 201) : json(actionsReadyResult, 201),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });

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
    await screen.findByRole("button", { name: "对话列表" });

    await user.type(screen.getByLabelText("输入你的问题"), "四个问题一起问");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText(/请拆成更小的问题/)).toBeInTheDocument();
    const inspector = await openInspector(user);
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
    await screen.findByRole("button", { name: "对话列表" });

    await user.type(screen.getByLabelText("输入你的问题"), "这个怎么弄");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const inspector = await openInspector(user);
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
    await screen.findByRole("button", { name: "对话列表" });

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
    await screen.findByRole("button", { name: "对话列表" });

    await user.type(screen.getByLabelText("输入你的问题"), "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText(/本轮理解失败.*可以重新发送/)).toBeInTheDocument();
    expect(screen.getByText(/这一轮没能加载近期历史/)).toBeInTheDocument();
    const inspector = await openInspector(user);
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
    await screen.findByRole("button", { name: "对话列表" });

    const editor = screen.getByLabelText("输入你的问题");
    await user.type(editor, "退货政策是什么？");
    await user.click(screen.getByRole("button", { name: "发送" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("消息没有保存成功，本轮没有开始处理。");
    expect(alert).toHaveTextContent("输入已保留，请直接重试发送。");
    expect(editor).toHaveValue("退货政策是什么？");
  });
});

describe("问答 C — 对话管理与分页", () => {
  it("重命名带 expectedVersion，版本冲突时保留表单不覆盖输入", async () => {
    const api = mockApi({
      rename: () => problem(409, "CONVERSATION_VERSION_CONFLICT", { currentVersion: 5 }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await user.click(await openListItem(user, /退货要什么材料/));
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
    await user.click(await openListItem(user, /退货要什么材料/));
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
    await user.click(await openListItem(user, /退货要什么材料/));
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
    // 翻页发生在抽屉里，抽屉不会因为加载更多而关闭，所以整段都在同一个容器里断言。
    const list = await openList(user);
    await within(list).findByRole("button", { name: /退货要什么材料/ });

    await user.click(within(list).getByRole("button", { name: "加载更多对话" }));

    await waitFor(() => expect(api.of("GET", "cursor=cursor-2")).toHaveLength(1));
    expect(await within(list).findByRole("button", { name: /清关材料/ })).toBeInTheDocument();
    expect(within(list).getByRole("button", { name: /退货要什么材料/ })).toBeInTheDocument();
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

    // 选中一项就关抽屉，所以每次切换都要重新打开——这本身也是 C 的行为断言。
    await user.click(await openListItem(user, /退货要什么材料/));
    await user.type(screen.getByLabelText("输入你的问题"), "还没发出去的草稿");

    await user.click(await openListItem(user, /清关材料/));
    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).toHaveValue(""));

    await user.click(await openListItem(user, /退货要什么材料/));
    await waitFor(() =>
      expect(screen.getByLabelText("输入你的问题")).toHaveValue("还没发出去的草稿"),
    );
  });
});

describe("问答 C — 响应式与键盘", () => {
  // 改判为 C 之后这一组验的不再是"哪一档收起哪一栏"，而是**每一档都没有常驻栏**：
  // 三档跑同一套断言，任何一档冒出常驻面板都算回归（ui-spec §2.2 / §10）。
  it.each([
    ["1440px", 1440, 900],
    ["1024px", 1024, 768],
    ["375px", 375, 812],
  ])("%s 下两侧面板都是抽屉，没有常驻栏", async (_name, width, height) => {
    setTestViewport(width, height);
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });

    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: "本轮处理" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    expect(await screen.findByRole("dialog", { name: "本轮处理" })).toBeInTheDocument();
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    await user.click(toggle);
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "本轮处理" })).not.toBeInTheDocument(),
    );
  });

  it("对话列表抽屉可开可关，Esc 关闭后焦点回到触发按钮", async () => {
    setTestViewport(375, 812);
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);

    const trigger = await screen.findByRole("button", { name: "对话列表" });
    await user.click(trigger);
    expect(await screen.findByRole("button", { name: "新对话" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("button", { name: "新对话" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it("本轮处理抽屉 Esc 关闭后焦点回到触发按钮", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<ChatPage />);

    const trigger = await screen.findByRole("button", { name: "本轮处理" });
    await user.click(trigger);
    expect(await screen.findByRole("heading", { name: "本轮处理" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "本轮处理" })).not.toBeInTheDocument(),
    );
    expect(trigger).toHaveFocus();
  });
});

/**
 * 工单 12 的组件用例，对应 3B-UI-01~04 的自动化部分（那四条本身是 M — 手工走查）。
 *
 * 贯穿全组的一条纪律来自 ui-spec §13.1 不变量 2：**抽屉默认关着，所以凡是影响结果可信度
 * 或需要用户决定的事实都必须在消息区有落点**。因此这里的断言大多刻意**不打开抽屉**——
 * 一条事实只在抽屉里可见，就等于绝大多数用户看不到它。
 */
describe("问答 C — 3B 来源与降级", () => {
  /**
   * 来源条目里有指向分块页的 `<Link>`，它要 Router context——所以本组的 render 必须包
   * MemoryRouter。既有几组不受影响：没有 retrieval 就不渲染来源区，也就没有 Link。
   */
  async function askWith(retrieval: unknown, overrides: Partial<Routes> = {}) {
    const api = mockApi({ submit: () => json(resultWith(retrieval), 201), ...overrides });
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );
    await user.type(screen.getByLabelText("输入你的问题"), "退货需要哪些材料？");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(1));
    return { api, user };
  }

  it("来源三项齐全且全页不出现分数、名次数字与命中通道", async () => {
    const { user } = await askWith(retrievedBlock);

    // 摘要行在抽屉关着时就说清了"有几条依据"。
    expect(await screen.findByRole("button", { name: /依据 2 条来源/ })).toBeInTheDocument();

    const inspector = await openInspector(user);
    // 两条来源同属一个库，所以是 getAll——库名按条重复是对的，界面不做分组合并。
    expect(within(inspector).getAllByText("售后服务知识库")).toHaveLength(2);
    expect(within(inspector).getByText("退货与退款操作手册 v3.docx")).toBeInTheDocument();
    expect(within(inspector).getByText("#12")).toBeInTheDocument();

    // 顺序即排名：不出现名次数字，也不出现任何分数或通道名。
    expect(within(inspector).queryByText(/第\s*1\s*名|排名|名次/)).not.toBeInTheDocument();
    expect(within(inspector).queryByText(/0\.9|91%|相关度/)).not.toBeInTheDocument();
    for (const channel of ["INTENT_TARGETED", "GLOBAL_VECTOR", "KEYWORD", "命中通道"]) {
      expect(within(inspector).queryByText(new RegExp(channel))).not.toBeInTheDocument();
    }
  });

  it("未精排在抽屉关着时也可见且不区分成因", async () => {
    const { user } = await askWith({
      ...retrievedBlock,
      rerankApplied: false,
      degradation: "RERANK_UNAVAILABLE",
    });

    // 关键：不打开抽屉就能看到。
    const marks = await screen.findAllByText("未精排");
    expect(marks.length).toBeGreaterThan(0);
    // 成因只进处理详情，不进面向用户的标记。
    expect(screen.queryByText(/RERANK_UNAVAILABLE|无 Key|Noop/)).not.toBeInTheDocument();

    const inspector = await openInspector(user);
    expect(within(inspector).getAllByText("未精排").length).toBeGreaterThan(0);
  });

  it("部分失败点名该库、给两个动作，且重试只调重试端点", async () => {
    const partial = {
      ...retrievedBlock,
      partial: true,
      failedTargets: [
        {
          knowledgeBaseId: 31,
          knowledgeBaseName: "海外仓运营手册",
          failureCode: "RETRIEVAL_TIMEOUT",
        },
      ],
    };
    const { api, user } = await askWith(partial);

    expect(await screen.findByText(/「海外仓运营手册」这个知识库本轮没有查成/)).toBeInTheDocument();
    const retry = screen.getByRole("button", { name: "重试失败的知识库" });
    expect(screen.getByRole("button", { name: "就现有结果继续" })).toBeInTheDocument();
    // 归一化原因落在抽屉的失败项里，消息区那句只点名库。
    expect(screen.queryByText("RETRIEVAL_TIMEOUT")).not.toBeInTheDocument();

    await user.click(retry);

    await waitFor(() => expect(api.of("POST", "/retrieval/retry")).toHaveLength(1));
    // 重试不新建 turn。**必须用 endsWith 精确匹配**：retry 的 URL 也含 "/turns"，
    // 用 includes 会把重试自己数成一次新提交。
    const submits = api.calls.filter(
      (call) => call.method === "POST" && call.url.endsWith("/api/qa/turns"),
    );
    expect(submits).toHaveLength(1);
    expect(api.of("POST", "/retrieval/retry")[0]?.body).toHaveProperty("clientRequestId");
  });

  it("就现有结果继续只收起提示，不改来源区的结果不完整标记", async () => {
    const { user } = await askWith({
      ...retrievedBlock,
      partial: true,
      failedTargets: [
        { knowledgeBaseId: 31, knowledgeBaseName: "海外仓运营手册", failureCode: "RETRIEVAL_TIMEOUT" },
      ],
    });

    await user.click(await screen.findByRole("button", { name: "就现有结果继续" }));
    expect(screen.queryByText(/这个知识库本轮没有查成/)).not.toBeInTheDocument();

    // 服务端状态没变，标记必须还在——否则"继续"会被读成"这轮结果其实是完整的"。
    const inspector = await openInspector(user);
    expect(within(inspector).getAllByText("结果不完整").length).toBeGreaterThan(0);
  });

  it("无结果文案与 3A 的方向不明可区分且没有用通用知识回答的入口", async () => {
    await askWith({ ...retrievedBlock, status: "NO_RESULT", sources: [] });

    expect(await screen.findByText("没有在你的知识库里找到相关内容。")).toBeInTheDocument();
    // 3A 的 NO_MATCH 说的是方向不明，两句不能混。
    expect(screen.queryByText(/还不能确定应该从哪个方向查找/)).not.toBeInTheDocument();
    expect(screen.getByText("换个说法再问一次")).toBeInTheDocument();
    // PRD §12.3：没有这个按钮，也没有这个开关。
    expect(screen.queryByRole("button", { name: /通用知识|直接回答|不用知识库/ })).not.toBeInTheDocument();
    // 没有来源时不摆一个"依据 0 条"的空入口。
    expect(screen.queryByRole("button", { name: /依据 0 条来源/ })).not.toBeInTheDocument();
  });

  it("全部失败与无结果的文案不同", async () => {
    await askWith({
      ...retrievedBlock,
      status: "RETRIEVAL_FAILED",
      sources: [],
      partial: false,
      failedTargets: [
        { knowledgeBaseId: 31, knowledgeBaseName: "海外仓运营手册", failureCode: "RETRIEVAL_TIMEOUT" },
      ],
    });

    expect(await screen.findByText(/本轮检索没有查成/)).toBeInTheDocument();
    expect(screen.queryByText("没有在你的知识库里找到相关内容。")).not.toBeInTheDocument();
  });

  it("文档已删除时条目保留但跳转被禁用", async () => {
    const { user } = await askWith(retrievedBlock);
    const inspector = await openInspector(user);

    expect(within(inspector).getByText("文档已删除")).toBeInTheDocument();
    expect(within(inspector).getByText("#4")).toBeInTheDocument();
    const disabled = within(inspector)
      .getAllByRole("button", { name: "打开分块页" })
      .filter((button) => (button as HTMLButtonElement).disabled);
    expect(disabled).toHaveLength(1);
    // 未删除的那条是真链接。
    expect(within(inspector).getByRole("link", { name: "打开分块页" })).toHaveAttribute(
      "href",
      "/knowledge-bases/12/documents/517/chunks",
    );
  });

  it("未进过 3B 的轮次不显示来源区也不显示 3B 验收措辞", async () => {
    const { user } = await askWith(null);

    expect(await screen.findByText(/当前里程碑尚未执行知识库检索或生成答案/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /依据/ })).not.toBeInTheDocument();

    const inspector = await openInspector(user);
    // 两种空态必须分开：这是"没检索过"，不是"没召回"。
    expect(within(inspector).getByText("本轮未执行检索")).toBeInTheDocument();
    expect(within(inspector).queryByText("本轮没有召回任何来源")).not.toBeInTheDocument();
  });
});
