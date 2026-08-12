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

function sse(frames: string): Response {
  return new Response(frames, {
    status: 200,
    headers: { "content-type": "text/event-stream;charset=UTF-8" },
  });
}

function completedStream(result: typeof actionsReadyResult): Response {
  const conversationId = result.conversation?.conversationId ?? CONVERSATION_ID;
  const turnId = result.turn.turnId;
  return sse([
    `event:accepted\nid:1\ndata:${JSON.stringify({ sequence: 1, conversationId, turnId, taskId: "task-1", userMessageId: result.turn.userMessageId })}`,
    `event:generation_started\nid:2\ndata:${JSON.stringify({ sequence: 2, queuedOnce: false })}`,
    `event:completed\nid:3\ndata:${JSON.stringify({ sequence: 3, assistantMessageId: null, saved: false })}`,
    "",
  ].join("\n\n"));
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
  stream?: (body: Record<string, unknown> | undefined, index: number) => Response | Promise<Response>;
  stop: () => Response;
  rename: (body: Record<string, unknown> | undefined) => Response;
  remove: () => Response;
  retryRetrieval: (body: Record<string, unknown> | undefined) => Response;
  toolDecision: (body: Record<string, unknown> | undefined) => Response;
  toolRetry: (body: Record<string, unknown> | undefined) => Response;
}

const defaultRoutes: Routes = {
  list: () => json({ items: [listItem], nextCursor: null }),
  // 切换对话必须真的换上另一个 conversationId，否则"草稿按对话隔离"这条断言会被
  // 一个恒定 id 的假响应蒙混过去。
  detail: (url) => json({ ...detail, conversationId: url.split("/").pop() }),
  messages: () => json({ items: [userMessage], previousCursor: null }),
  turn: () => json({ turn: actionsReadyTurn, inspector: actionsReadyInspector, assistantMessage: null, clarification: null }),
  submit: () => json(actionsReadyResult, 201),
  stop: () => json({ status: "STOPPED", alreadyTerminal: false }),
  rename: (body) => json({ conversationId: CONVERSATION_ID, title: String(body?.title), version: 3 }),
  remove: () => json(null, 204),
  retryRetrieval: () => json(resultWith({ ...retrievedBlock, partial: false, failedTargets: [] })),
  toolDecision: () => json({ tools: [] }),
  toolRetry: () => json({ tools: [] }),
};

function mockApi(overrides: Partial<Routes> = {}) {
  const routes = { ...defaultRoutes, ...overrides };
  const calls: Recorded[] = [];
  let submits = 0;
  let latestSubmitted: typeof actionsReadyResult | null = null;

  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined;
    calls.push({ url, method, body });

    if (method === "POST" && url.includes("/decision")) {
      return Promise.resolve(routes.toolDecision(body));
    }
    if (method === "POST" && url.includes("/tool-calls/") && url.includes("/retry")) {
      return Promise.resolve(routes.toolRetry(body));
    }
    if (method === "POST" && url.includes("/retrieval/retry")) {
      return Promise.resolve(routes.retryRetrieval(body));
    }
    if (method === "POST" && url.includes("/generation-tasks/") && url.endsWith("/stop")) {
      return Promise.resolve(routes.stop());
    }
    if (method === "POST" && url.endsWith("/api/qa/turns:stream")) {
      if (routes.stream) return Promise.resolve(routes.stream(body, submits++));
      return Promise.resolve(routes.submit(body, submits++)).then(async (response) => {
        if (!response.ok) return response;
        latestSubmitted = await response.clone().json() as typeof actionsReadyResult;
        return completedStream(latestSubmitted);
      });
    }
    if (method === "PATCH") return Promise.resolve(routes.rename(body));
    if (method === "DELETE") return Promise.resolve(routes.remove());
    if (url.includes("/messages")) return Promise.resolve(routes.messages(url));
    if (url.includes("/turns/")) return Promise.resolve(latestSubmitted ? json(latestSubmitted) : routes.turn());
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

describe("问答 C — 3C 流式生成", () => {
  const encoder = new TextEncoder();

  function frame(event: string, sequence: number, data: Record<string, unknown> = {}) {
    return encoder.encode(`event:${event}\nid:${sequence}\ndata:${JSON.stringify({ sequence, ...data })}\n\n`);
  }

  function accepted(controller: ReadableStreamDefaultController<Uint8Array>) {
    controller.enqueue(frame("accepted", 1, {
      conversationId: CONVERSATION_ID,
      turnId: "t-stream",
      taskId: "task-stream",
      userMessageId: "m-stream",
    }));
  }

  function finalResult(status: "COMPLETED" | "STOPPED", content: string | null) {
    return {
      ...actionsReadyResult,
      turn: { ...actionsReadyTurn, turnId: "t-stream", userMessageId: "m-stream", status },
      generation: {
        taskId: "task-stream", status, queuedOnce: true,
        answerSaved: content !== null, assistantMessageId: content === null ? null : "a-stream",
      },
      assistantMessage: content === null ? null : {
        messageId: "a-stream", turnId: "t-stream", sequenceNo: 2, role: "ASSISTANT",
        type: "ASSISTANT_ANSWER", content, createdAt: "2026-08-12T16:00:00+08:00",
        incomplete: status === "STOPPED",
      },
    };
  }

  it("排队后原地生成纯文本并以服务端完成态收敛", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const completed = finalResult("COMPLETED", "第一行\n第二行");
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => new Response(new ReadableStream({ start(value) {
        controller = value;
        accepted(value);
        value.enqueue(frame("queued", 2));
      } }), { headers: { "content-type": "text/event-stream" } }),
      turn: () => json(completed),
      messages: () => json({ items: [userMessage, completed.assistantMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "流式回答");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText("排队中…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "停止生成" })).toBeInTheDocument();
    expect(screen.getByLabelText("输入你的问题")).toBeDisabled();

    controller.enqueue(frame("generation_started", 3, { queuedOnce: true }));
    controller.enqueue(frame("answer_delta", 4, { text: "第一行\n第二行" }));
    const liveAnswer = await screen.findByText((_, element) => element?.textContent === "第一行\n第二行");
    expect(liveAnswer).toBeInTheDocument();
    expect(liveAnswer.closest("article")).toHaveAttribute("aria-busy", "true");

    controller.enqueue(frame("completed", 5, { assistantMessageId: "a-stream", saved: true }));
    controller.close();
    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).not.toBeDisabled());
    expect(screen.queryByRole("button", { name: "停止生成" })).not.toBeInTheDocument();
  });

  it("生成中停止立即保留部分答案且忽略后到delta", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stopped = finalResult("STOPPED", "已有部分");
    const api = mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => new Response(new ReadableStream({ start(value) {
        controller = value;
        accepted(value);
        value.enqueue(frame("generation_started", 2, { queuedOnce: false }));
        value.enqueue(frame("answer_delta", 3, { text: "已有部分" }));
      } })),
      turn: () => json(stopped),
      messages: () => json({ items: [userMessage, stopped.assistantMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "请详细回答");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByText("已有部分");

    await user.click(screen.getByRole("button", { name: "停止生成" }));
    expect(await screen.findByText("已停止")).toBeInTheDocument();
    expect(screen.getByText("已停止生成，以上是已经产生的部分。")).toBeInTheDocument();
    expect(api.of("POST", "/generation-tasks/task-stream/stop")).toHaveLength(1);
    controller.enqueue(frame("answer_delta", 4, { text: "不应追加" }));
    expect(screen.queryByText(/不应追加/)).not.toBeInTheDocument();
    controller.enqueue(frame("stopped", 5, {
      assistantMessageId: "a-stream", saved: true, hasPartialAnswer: true,
    }));
    controller.close();
    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).not.toBeDisabled());
    expect(screen.queryByRole("button", { name: /重试/ })).not.toBeInTheDocument();
  });

  it("排队中也可立即停止且不伪造答案", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stopped = finalResult("STOPPED", null);
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => new Response(new ReadableStream({ start(value) {
        controller = value;
        accepted(value);
        value.enqueue(frame("queued", 2));
      } })),
      turn: () => json(stopped),
      messages: () => json({ items: [userMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "排队问题");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByText("排队中…");
    await user.click(screen.getByRole("button", { name: "停止生成" }));
    expect(await screen.findByText("已停止")).toBeInTheDocument();
    expect(screen.getByText("已停止，本轮没有开始生成。")).toBeInTheDocument();
    expect(screen.queryByText("已停止生成，以上是已经产生的部分。")).not.toBeInTheDocument();
    expect(screen.queryByText("助手回答")).not.toBeInTheDocument();
    controller.enqueue(frame("stopped", 3, {
      assistantMessageId: null, saved: false, hasPartialAnswer: false,
    }));
    controller.close();
  });

  it.each(["COMPLETED", "STOPPED"] as const)("%s但未保存时保留屏上答案并显示独立警告", async (status) => {
    const terminal = finalResult(status, null);
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => sse([
        `event:accepted\nid:1\ndata:${JSON.stringify({ sequence: 1, conversationId: CONVERSATION_ID, turnId: "t-stream", taskId: "task-stream", userMessageId: "m-stream" })}`,
        `event:generation_started\nid:2\ndata:${JSON.stringify({ sequence: 2, queuedOnce: false })}`,
        `event:answer_delta\nid:3\ndata:${JSON.stringify({ sequence: 3, text: "只在当前页面可见" })}`,
        `event:${status === "COMPLETED" ? "completed" : "stopped"}\nid:4\ndata:${JSON.stringify({ sequence: 4, assistantMessageId: null, saved: false, hasPartialAnswer: true })}`,
        "",
      ].join("\n\n")),
      turn: () => json(terminal),
      messages: () => json({ items: [userMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "未保存测试");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText("只在当前页面可见")).toBeInTheDocument();
    expect(await screen.findByText("本条回答未能保存，刷新后将不可见")).toBeInTheDocument();
    if (status === "STOPPED") {
      expect(screen.getByText("已停止生成，以上是已经产生的部分。")).toBeInTheDocument();
    }
    expect(screen.getByLabelText("输入你的问题")).not.toBeDisabled();
  });

  it("流断开后只显示处理中提示并轮询到完整终态而不假流", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    let reads = 0;
    const completed = finalResult("COMPLETED", "恢复后的完整答案");
    const activeResult = {
      ...completed,
      turn: { ...completed.turn, status: "GENERATING", completedAt: null },
      assistantMessage: null,
      generation: { ...completed.generation, status: "GENERATING", answerSaved: null, assistantMessageId: null },
      retryAfterMs: 1,
    };
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => new Response(new ReadableStream({ start(value) {
        controller = value;
        accepted(value);
        value.enqueue(frame("generation_started", 2, { queuedOnce: false }));
      } })),
      turn: () => json(reads++ === 0 ? activeResult : completed),
      messages: () => json({ items: reads > 1 ? [userMessage, completed.assistantMessage] : [userMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "断连恢复");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByText("正在生成…");

    controller.error(new Error("connection lost"));
    expect(await screen.findByText("这一轮仍在进行，完成后会显示在这里")).toBeInTheDocument();
    expect(screen.queryByText("恢复后的完整答案")).not.toBeInTheDocument();
    expect(document.querySelector(".qa-live-answer")).toHaveAttribute("aria-busy", "true");
    expect(document.querySelector(".qa-live-answer")?.closest("[aria-live]")).toBeNull();

    expect(await screen.findByText("恢复后的完整答案", {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getByLabelText("输入你的问题")).not.toBeDisabled();
  });

  it.each([
    ["FAILED", "MODEL_UNAVAILABLE", "回答服务暂时不可用，请稍后重试。"],
    ["TIMED_OUT", "GENERATION_WAIT_TIMEOUT", "当前排队较多，本轮未开始生成"],
  ])("%s终态使用独立归一化文案并提供重试", async (status, reason, message) => {
    const failed = {
      ...actionsReadyResult,
      turn: { ...actionsReadyTurn, turnId: "t-stream", userMessageId: "m-stream", status, terminalReason: reason },
      generation: {
        taskId: "task-stream", status, queuedOnce: status === "TIMED_OUT",
        answerSaved: status === "FAILED" ? false : null, assistantMessageId: null,
      },
    };
    mockApi({
      list: () => json({ items: [], nextCursor: null }),
      stream: () => sse([
        `event:accepted\nid:1\ndata:${JSON.stringify({ sequence: 1, conversationId: CONVERSATION_ID, turnId: "t-stream", taskId: "task-stream", userMessageId: "m-stream" })}`,
        `event:${status === "FAILED" ? "failed" : "timed_out"}\nid:2\ndata:${JSON.stringify({ sequence: 2, code: reason, retryable: true, assistantMessageId: null, saved: false })}`,
        "",
      ].join("\n\n")),
      turn: () => json(failed),
      messages: () => json({ items: [userMessage], previousCursor: null }),
    });
    const user = userEvent.setup();
    render(<ChatPage />);
    await screen.findByRole("button", { name: "对话列表" });
    await user.type(screen.getByLabelText("输入你的问题"), "失败重试");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
  });
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
    expect(screen.getByText("正在保存…")).toBeInTheDocument();
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
      (call) => call.method === "POST" && call.url.endsWith("/api/qa/turns:stream"),
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

/**
 * 工单 13 的组件用例，对应 3B-UI-05 / 06 / 08 的自动化部分（那三条本身是 M — 手工走查）。
 *
 * 重点在三条最容易写错、而且错了在评审里看不出来的规则：确认卡不抢焦点、Tab 先到取消、
 * Esc 不等于取消。它们都是"某个东西**不该**发生"，只有测试能守住。
 */
describe("问答 C — 3B 工具区与确认卡", () => {
  const pendingTool = {
    toolCallId: "tc-1",
    ordinal: 0,
    toolName: "submit-return-ticket",
    displayName: "提交退货工单",
    description: "在售后工单系统中新建一张退货工单，并把工单号回写到本次对话。",
    sideEffect: true,
    status: "PENDING_CONFIRM",
    parameters: [
      { name: "订单号", value: "ORD-20260806-1183", required: true },
      { name: "联系电话", value: null, required: false },
    ],
    resultText: null,
    failureCode: null,
    latencyMs: null,
  };

  function resultWithTools(tools: unknown[]) {
    return { ...actionsReadyResult, retrieval: null, tools };
  }

  async function askWithTools(tools: unknown[], overrides: Partial<Routes> = {}) {
    const api = mockApi({ submit: () => json(resultWithTools(tools), 201), ...overrides });
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );
    await user.type(screen.getByLabelText("输入你的问题"), "帮我提交退货工单");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(api.of("POST", "/turns")).toHaveLength(1));
    return { api, user };
  }

  it("确认卡不抢焦点、Tab 先到取消、Esc 不取消工具", async () => {
    const { api, user } = await askWithTools([pendingTool]);

    const card = await screen.findByRole("status", { name: /需要你确认：提交退货工单/ });
    // 不抢焦点：危险确认的默认焦点不该落在危险按钮上。
    expect(within(card).getByRole("button", { name: "确认执行" })).not.toHaveFocus();
    expect(within(card).getByRole("button", { name: "取消" })).not.toHaveFocus();

    // Tab 先到取消。
    await user.tab();
    let guard = 0;
    while (
      document.activeElement !== within(card).getByRole("button", { name: "取消" }) &&
      document.activeElement !== within(card).getByRole("button", { name: "确认执行" }) &&
      guard++ < 30
    ) {
      await user.tab();
    }
    expect(within(card).getByRole("button", { name: "取消" })).toHaveFocus();

    // Esc 不等于取消：误触不该丢掉一份已抽取好的参数。
    await user.keyboard("{Escape}");
    expect(screen.getByRole("status", { name: /需要你确认/ })).toBeInTheDocument();
    expect(api.calls.filter((call) => call.url.includes("/decision"))).toHaveLength(0);
  });

  it("说明与参数来自服务端且不出现Schema原文或参数不可编辑的输入框", async () => {
    await askWithTools([pendingTool]);
    const card = await screen.findByRole("status", { name: /需要你确认/ });

    expect(within(card).getByText(/在售后工单系统中新建一张退货工单/)).toBeInTheDocument();
    expect(within(card).getByText("由模型从对话中抽取")).toBeInTheDocument();
    expect(within(card).getByText("订单号")).toBeInTheDocument();
    expect(within(card).getByText("ORD-20260806-1183")).toBeInTheDocument();
    // 可选参数缺省时条目仍在，显示"未提供"而不是消失。
    expect(within(card).getByText("联系电话")).toBeInTheDocument();
    expect(within(card).getByText("未提供")).toBeInTheDocument();

    // 参数在 3B 不可就地编辑（P-3B-02）：卡片里不该有任何输入控件。
    expect(within(card).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(card).queryByRole("combobox")).not.toBeInTheDocument();
    // Schema 原文与属性名都不出去。
    expect(card.textContent).not.toContain("orderNo");
    expect(card.textContent).not.toContain("additionalProperties");
  });

  it("确认走裁决端点且执行中禁用输入区并且没有中止入口", async () => {
    const running = { ...pendingTool, status: "RUNNING" };
    const { api, user } = await askWithTools([pendingTool], {
      // 裁决后返回执行中，用来验输入区禁用与"没有中止按钮"。
      toolDecision: () => json({ tools: [running] }),
    });

    await user.click(await screen.findByRole("button", { name: "确认执行" }));

    await waitFor(() => expect(api.of("POST", "/decision")).toHaveLength(1));
    expect(api.of("POST", "/decision")[0]?.body).toMatchObject({ decision: "CONFIRM" });

    // 执行中：输入区禁用。
    await waitFor(() => expect(screen.getByLabelText("输入你的问题")).toBeDisabled());
    // 3B 没有停止能力，也不摆一个点了没用的按钮（ui-spec §13.1 不变量 4）。
    expect(screen.queryByRole("button", { name: /中止|停止|取消执行/ })).not.toBeInTheDocument();
  });

  it("取消走同一个端点的另一个取值", async () => {
    const cancelled = { ...pendingTool, status: "CANCELLED" };
    const { api, user } = await askWithTools([pendingTool], {
      toolDecision: () => json({ tools: [cancelled] }),
    });

    await user.click(await screen.findByRole("button", { name: "取消" }));

    await waitFor(() => expect(api.of("POST", "/decision")).toHaveLength(1));
    expect(api.of("POST", "/decision")[0]?.body).toMatchObject({ decision: "CANCEL" });
    // 卡片就地转为静态记录：按钮消失，不从时间线里抹掉。
    expect(screen.queryByRole("button", { name: "确认执行" })).not.toBeInTheDocument();
  });

  it("工具返回按纯文本渲染不产生可点击目标", async () => {
    const succeeded = {
      ...pendingTool,
      status: "SUCCEEDED",
      resultText: '工单号 RT-1 <a href="https://evil.example">点我</a> [看这里](https://evil.example)',
      latencyMs: 120,
    };
    const { user } = await askWithTools([succeeded]);
    const inspector = await openInspector(user);

    await user.click(within(inspector).getByRole("button", { name: "展开参数" }));
    // 原样显示成文本，而不是被解析成链接。
    expect(within(inspector).getByText(/evil\.example/)).toBeInTheDocument();
    expect(within(inspector).queryByRole("link", { name: "点我" })).not.toBeInTheDocument();
    expect(within(inspector).queryByRole("link", { name: "看这里" })).not.toBeInTheDocument();
    expect(within(inspector).getByText("120 ms")).toBeInTheDocument();
  });

  it("失败给归一化原因与重试且不透传异常message", async () => {
    const failed = {
      ...pendingTool,
      status: "FAILED",
      failureCode: "EXECUTION_TIMEOUT",
      latencyMs: 10000,
    };
    const { api, user } = await askWithTools([failed], {
      toolRetry: () => json({ tools: [{ ...pendingTool, toolCallId: "tc-2" }] }),
    });

    expect(await screen.findByText(/执行失败：执行超时/)).toBeInTheDocument();
    // 闭集码值不直接示人。
    expect(screen.queryByText("EXECUTION_TIMEOUT")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "重试" }));

    await waitFor(() => expect(api.of("POST", "/retry")).toHaveLength(1));
    // 有副作用的工具重试后重新出确认卡——上一次的确认不可复用（ui-spec §13.7.4）。
    expect(await screen.findByRole("status", { name: /需要你确认/ })).toBeInTheDocument();
  });

  it("不可用与已中止各自有克制的提示且没有继续执行入口", async () => {
    await askWithTools([
      { ...pendingTool, toolCallId: "tc-u", status: "UNAVAILABLE", parameters: [] },
      { ...pendingTool, toolCallId: "tc-a", status: "ABORTED", ordinal: 1 },
    ]);

    expect(await screen.findByText(/不在当前可用工具清单中，本轮未执行/)).toBeInTheDocument();
    expect(screen.getByText(/还缺必要信息，本轮未执行/)).toBeInTheDocument();
    // 禁止用默认值继续（PRD §12.7）：界面上不存在这条路径。
    expect(screen.queryByRole("button", { name: /继续执行|跳过|使用默认值/ })).not.toBeInTheDocument();
    // 两句必须不同——一个是配置缺失、一个是参数没抽出来。
    expect(screen.queryByText(/不在当前可用工具清单中.*还缺必要信息/)).not.toBeInTheDocument();
  });

  it("没有工具调用时抽屉里是专门的空态", async () => {
    const { user } = await askWithTools([]);
    const inspector = await openInspector(user);

    expect(within(inspector).getByText("本轮没有调用工具")).toBeInTheDocument();
    // 与"工具不可用"不是同一句。
    expect(within(inspector).queryByText(/不在当前可用工具清单/)).not.toBeInTheDocument();
  });
});
