import { useEffect, useRef, useState } from "react";
import {
  listQaConversations,
  listQaMessages,
  readQaConversation,
  decideQaToolCall,
  readQaTurn,
  retryQaRetrieval,
  retryQaToolCall,
  stopQaGeneration,
  streamQaQuestion,
  submitQaClarification,
  toQaUserMessage,
  type ConversationDetail,
  type ConversationListItem,
  type QaMessage,
  type TurnResult,
} from "../api/qa";
import type { UserFacingMessage } from "../api/errors";
import { ConversationListPane } from "./chat/ConversationListPane";
import { DeleteConversationDialog, RenameConversationDialog } from "./chat/ConversationDialogs";
import { Drawer } from "./chat/Drawer";
import { TurnResultPane } from "./chat/TurnResultPane";
import { PartialMark, RerankMark } from "./chat/TurnSourcesPane";
import { ToolConfirmCard } from "./chat/ToolConfirmCard";
import { relativeTime, toolFailureLabel } from "./chat/labels";

const COMPLETION_NOTICE =
  "已完成问题理解并形成后续检索动作。当前里程碑尚未执行知识库检索或生成答案。";

/** 进过 3B 的轮次改用这句（ui-spec §13.5）。两句都带阶段标签，正式交付后一起删除。 */
const RETRIEVAL_COMPLETION_NOTICE = "已完成知识库检索。当前里程碑尚未生成答案。";

/** 新对话在草稿表里的键。用空串而不是 null，省掉一层 `Map | null` 的分支。 */
const NEW_CONVERSATION = "";

/** 单选组里"都不是这些"的本地标记值，不会作为 nodeId 发给服务端。 */
const NONE_OF_THE_ABOVE = "__none_of_the_above__";

type LiveGeneration = {
  phase: "submitting" | "queued" | "generating" | "recovering" | "completed" | "stopped" | "failed" | "timed_out";
  conversationId: string | null;
  turnId: string | null;
  taskId: string | null;
  answer: string;
  saved: boolean | null;
  failureCode: string | null;
};

/**
 * 问答页（approved C — Focused Chat，2026-08-08 由 A 改判）。
 *
 * 布局只有一种形态：**中央单列，对话列表与本轮处理都是抽屉**，桌面也不例外
 * （ui-spec §2.2 / §12A）。因此这里不再消费 `useViewportLayout`——没有"常驻栏 vs 抽屉"
 * 的分支要按断点切，剩下的宽度差异纯 CSS 就够。断点判定仍留给意图树页使用。
 *
 * 四条不能松的纪律：
 *
 * 1. **只展示真实 3A 状态**——没有伪答案、伪来源、停止或排队控件，3A 完成提示带
 *    `3A 验收` 标签且不进入历史助手消息。
 * 2. **同一次提交重试复用同一个 clientRequestId**，但正文一变就必须换新的：后端对
 *    "同 key 不同正文"返回 409 `IDEMPOTENCY_KEY_REUSED`，复用到底反而把用户卡死。
 * 3. **服务端是终态的唯一事实来源**——本地不推断"应该成功了"，一律以返回的 turn 为准。
 * 4. **本轮处理默认关着，所以它不承担关键失败的传达**（ui-spec §6.1）。失败与降级都要
 *    落在消息区，抽屉一次不开也不该丢信息。选 C 之后这条从保守设计变成唯一保险。
 */
export function ChatPage() {
  const [conversations, setConversations] = useState<ConversationListItem[]>([]);
  const [listCursor, setListCursor] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const [current, setCurrent] = useState<ConversationDetail | null>(null);
  const [messages, setMessages] = useState<QaMessage[]>([]);
  const [earlierCursor, setEarlierCursor] = useState<string | null>(null);
  const [result, setResult] = useState<TurnResult | null>(null);
  const [threadError, setThreadError] = useState<UserFacingMessage | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [generation, setGeneration] = useState<LiveGeneration | null>(null);
  const [lastQuestion, setLastQuestion] = useState<string | null>(null);
  const [choice, setChoice] = useState<string | null>(null);

  const [retrying, setRetrying] = useState(false);
  /** 正在裁决/重试的 toolCallId。同一时刻只允许一个，避免重复确认。 */
  const [toolBusy, setToolBusy] = useState<string | null>(null);
  /**
   * `就现有结果继续` 只收起提示，**不改服务端状态**——用户说的是"我知道了、别再挡我"，
   * 不是"把这轮标记成完整的"。partial 仍然是 true，来源区的 `结果不完整` 标记照旧。
   */
  const [partialDismissed, setPartialDismissed] = useState(false);

  const [menuOpen, setMenuOpen] = useState(false);
  const [dialog, setDialog] = useState<"rename" | "delete" | null>(null);
  const [panel, setPanel] = useState<"list" | "inspector" | null>(null);

  const pending = useRef<{ signature: string; requestId: string } | null>(null);
  const pollVersion = useRef(0);
  const panelTrigger = useRef<HTMLButtonElement | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);

  const draftKey = current?.conversationId ?? NEW_CONVERSATION;
  const draft = drafts[draftKey] ?? "";
  const status = result?.turn.status;
  const generationActive = generation?.phase === "submitting"
    || generation?.phase === "queued" || generation?.phase === "generating"
    || generation?.phase === "recovering";
  const active = submitting || generationActive || status === "SAVED" || status === "PROCESSING"
    || status === "QUEUED" || status === "GENERATING";
  const retrieval = result?.retrieval ?? null;
  /**
   * 失败库名拼给提示文案。**只取库名，不取 failureCode 的原始值**——消息区那句是给用户看的，
   * 归一化原因留在抽屉的失败项列表里（ui-spec §13.6）。
   */
  const tools = result?.tools ?? [];
  /**
   * 等待裁决的有副作用工具。只可能有一个——工具按顺序执行，前一个没裁决完不会产生下一个。
   */
  const pendingTool = tools.find(
    (tool) => tool.status === "PENDING_CONFIRM" && tool.sideEffect,
  );
  const unavailableTools = tools.filter((tool) => tool.status === "UNAVAILABLE");
  const abortedTools = tools.filter((tool) => tool.status === "ABORTED");
  /**
   * 工具执行中要禁用输入区（ui-spec §13.7.1）。**不提供中止入口**——3B 没有停止能力，
   * 摆一个点了没用的按钮比没有按钮更糟（§13.1 不变量 4）。
   */
  const toolRunning = tools.some((tool) => tool.status === "RUNNING");
  /**
   * 输入区的禁用条件比 `active` 多一项工具执行中。
   *
   * 待确认**不禁用**——那一态在等用户，沿用歧义等待的惯例，用户还可以用自然语言补充
   * （ui-spec §13.7.1 的状态表里那一行写得很明确）。只有真的在跑才禁用。
   */
  const composerLocked = active || toolRunning;
  const failedNames = (retrieval?.failedTargets ?? [])
    .map((target) => target.knowledgeBaseName)
    .filter((name): name is string => Boolean(name))
    .join("」「");
  const inspectorOpen = panel === "inspector";

  useEffect(() => {
    void loadConversations();
    return () => { pollVersion.current += 1; };
  }, []);

  /** 关闭覆盖层后把焦点还给触发它的按钮（ui-spec §10）。 */
  useEffect(() => {
    if (panel === null) {
      panelTrigger.current?.focus();
      panelTrigger.current = null;
    }
  }, [panel]);

  async function loadConversations(cursor?: string) {
    try {
      const page = await listQaConversations(cursor ? { cursor, limit: 20 } : { limit: 20 });
      setConversations((prev) => (cursor ? [...prev, ...page.items] : page.items));
      setListCursor(page.nextCursor);
      setListError(null);
    } catch (cause) {
      // 列表失败不清空已打开的对话：正在看的内容和列表可用性是两件事。
      setListError(toQaUserMessage(cause).title);
    } finally {
      setListLoading(false);
    }
  }

  async function refreshThread(conversationId: string) {
    const [detail, page] = await Promise.all([
      readQaConversation(conversationId),
      listQaMessages(conversationId, { limit: 50 }),
    ]);
    setCurrent(detail);
    setMessages(page.items);
    setLastQuestion([...page.items].reverse().find((message) => message.role === "USER")?.content ?? null);
    setEarlierCursor(page.previousCursor);
    return detail;
  }

  async function openConversation(conversationId: string) {
    const version = ++pollVersion.current;
    setThreadError(null);
    setNotice(null);
    setChoice(null);
    setPanel(null);
    try {
      const detail = await refreshThread(conversationId);
      const next = detail.latestTurn ? await readQaTurn(conversationId, detail.latestTurn.turnId) : null;
      setResult(next);
      if (next && isActiveTurn(next.turn.status) && next.generation) {
        setGeneration(recoveringGeneration(conversationId, next));
        void pollGeneration(conversationId, next.turn.turnId, version, next.retryAfterMs);
      } else {
        setGeneration(null);
      }
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    }
  }

  async function loadEarlier() {
    if (!current || !earlierCursor) return;
    try {
      const page = await listQaMessages(current.conversationId, {
        before: earlierCursor,
        limit: 50,
      });
      setMessages((prev) => [...page.items, ...prev]);
      setEarlierCursor(page.previousCursor);
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    }
  }

  function newConversation() {
    pollVersion.current += 1;
    setCurrent(null);
    setMessages([]);
    setEarlierCursor(null);
    setResult(null);
    setThreadError(null);
    setNotice(null);
    setChoice(null);
    setGeneration(null);
    setPanel(null);
    editor.current?.focus();
  }

  /**
   * 同一语义的提交复用同一个 key，语义一变立刻换新的。signature 就是"这次提交是什么"
   * 的完整描述，比"上次失败过所以复用"可靠。
   */
  function requestIdFor(signature: string) {
    if (pending.current?.signature === signature) {
      return pending.current.requestId;
    }
    const requestId = crypto.randomUUID();
    pending.current = { signature, requestId };
    return requestId;
  }

  /**
   * 重试失败的知识库（ui-spec §13.6 / api.md §11）。
   *
   * 服务端只重跑失败目标并把结果合并进同一轮，所以这里直接用返回的 turn 替换本地状态——
   * 前端不自己做合并，也不重新拉整个会话（改写与意图都没变，重拉只会让界面闪一下）。
   */
  /**
   * 裁决一次工具调用。
   *
   * 响应只回 tools（契约 §10 要求整个 turn，工单 10 的外壳没改），所以这里只合并那一块。
   * 直接整体替换 result 会把 retrieval 抹掉。
   */
  async function decideTool(toolCallId: string, decision: "CONFIRM" | "CANCEL") {
    if (!result || toolBusy) return;
    setToolBusy(toolCallId);
    setThreadError(null);
    try {
      const next = await decideQaToolCall({
        clientRequestId: requestIdFor(`tool-decision|${toolCallId}|${decision}`),
        turnId: result.turn.turnId,
        toolCallId,
        decision,
      });
      setResult((prev) => (prev ? { ...prev, tools: next.tools } : prev));
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    } finally {
      setToolBusy(null);
    }
  }

  /**
   * 重试失败的工具。有副作用的工具会得到一条**新的** toolCallId 并回到待确认——
   * 上一次的确认不可复用（ui-spec §13.7.4）。
   */
  async function retryTool(toolCallId: string) {
    if (!result || toolBusy) return;
    setToolBusy(toolCallId);
    setThreadError(null);
    try {
      const next = await retryQaToolCall({
        clientRequestId: requestIdFor(`tool-retry|${toolCallId}`),
        turnId: result.turn.turnId,
        toolCallId,
      });
      setResult((prev) => (prev ? { ...prev, tools: next.tools } : prev));
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    } finally {
      setToolBusy(null);
    }
  }

  async function retryRetrieval() {
    if (!result || retrying) return;
    setRetrying(true);
    setThreadError(null);
    try {
      const next = await retryQaRetrieval({
        clientRequestId: requestIdFor(`retry-retrieval|${result.turn.turnId}`),
        turnId: result.turn.turnId,
      });
      setResult(next);
      setPartialDismissed(false);
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    } finally {
      setRetrying(false);
    }
  }

  async function sendQuestion(retryText?: string) {
    const text = (retryText ?? draft).trim();
    if (!text || active) return;
    const conversationId = current?.conversationId ?? null;
    const key = draftKey;
    setSubmitting(true);
    setLastQuestion(text);
    setGeneration({
      phase: "submitting", conversationId, turnId: null, taskId: null,
      answer: "", saved: null, failureCode: null,
    });
    setThreadError(null);
    setNotice(null);
    let accepted: { conversationId: string; turnId: string } | null = null;
    try {
      await streamQaQuestion({
        clientRequestId: requestIdFor(`question|${conversationId ?? "new"}|${text}`),
        conversationId,
        text,
      }, (event) => {
        if (event.event === "accepted") {
          accepted = { conversationId: event.conversationId, turnId: event.turnId };
          setSubmitting(false);
          setGeneration((live) => live && ({ ...live,
            conversationId: event.conversationId, turnId: event.turnId, taskId: event.taskId,
            phase: "generating",
          }));
          setMessages((previous) => previous.some((message) => message.messageId === event.userMessageId)
            ? previous
            : [...previous, {
              messageId: event.userMessageId, turnId: event.turnId, sequenceNo: previous.length + 1,
              role: "USER", type: "USER_QUERY", content: text, createdAt: new Date().toISOString(),
            }]);
        } else if (event.event === "queued") {
          setGeneration((live) => live && ({ ...live, phase: "queued" }));
        } else if (event.event === "generation_started") {
          setGeneration((live) => live && ({ ...live, phase: "generating" }));
        } else if (event.event === "answer_delta") {
          setGeneration((live) => live && isLiveTerminal(live.phase)
            ? live : live && ({ ...live, answer: live.answer + event.text }));
        } else if (event.event === "completed" || event.event === "stopped") {
          setGeneration((live) => live && ({ ...live, phase: event.event, saved: event.saved }));
        } else if (event.event === "failed") {
          setGeneration((live) => live && ({ ...live,
            phase: "failed", saved: event.saved, failureCode: event.code,
          }));
        } else {
          setGeneration((live) => live && ({ ...live, phase: "timed_out" }));
        }
      });
      pending.current = null;
      setChoice(null);
      setDrafts((prev) => ({ ...prev, [key]: "" }));
      if (accepted) {
        const finalAccepted = accepted as { conversationId: string; turnId: string };
        const detail = await refreshThread(finalAccepted.conversationId);
        const next = await readQaTurn(finalAccepted.conversationId, finalAccepted.turnId);
        setResult(next);
        setCurrent(detail);
        setGeneration((live) => next.generation?.answerSaved === false && live?.answer
          ? live : null);
      }
      await loadConversations();
    } catch (cause) {
      if (accepted) {
        const disconnected = accepted as { conversationId: string; turnId: string };
        const version = ++pollVersion.current;
        setGeneration((live) => live && ({ ...live, phase: "recovering" }));
        void pollGeneration(disconnected.conversationId, disconnected.turnId, version);
      } else {
        setGeneration(null);
        setThreadError(toQaUserMessage(cause));
      }
    } finally {
      setSubmitting(false);
      editor.current?.focus();
    }
  }

  async function pollGeneration(
    conversationId: string,
    turnId: string,
    version: number,
    retryAfterMs = 1000,
  ) {
    await new Promise((resolve) => window.setTimeout(resolve, Math.max(100, retryAfterMs)));
    if (pollVersion.current !== version) return;
    try {
      const next = await readQaTurn(conversationId, turnId);
      if (pollVersion.current !== version) return;
      setResult(next);
      if (isActiveTurn(next.turn.status)) {
        void pollGeneration(conversationId, turnId, version, next.retryAfterMs);
        return;
      }
      setGeneration(null);
      await refreshThread(conversationId);
      await loadConversations();
    } catch {
      if (pollVersion.current === version) {
        void pollGeneration(conversationId, turnId, version, retryAfterMs);
      }
    }
  }

  async function stopGeneration() {
    if (!generation?.taskId || !generation.conversationId || !generation.turnId
      || !generationActive) return;
    const owned = {
      taskId: generation.taskId,
      conversationId: generation.conversationId,
      turnId: generation.turnId,
    };
    setGeneration((live) => live && ({ ...live, phase: "stopped" }));
    try {
      const stopped = await stopQaGeneration({
        taskId: owned.taskId, conversationId: owned.conversationId, turnId: owned.turnId,
      });
      setGeneration((live) => live && ({ ...live, phase: generationPhase(stopped.status) }));
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    }
  }

  async function chooseClarification(selectedNodeId: string | null) {
    if (!current || !result || submitting) return;
    const sourceTurnId = result.turn.turnId;
    setChoice(selectedNodeId ?? NONE_OF_THE_ABOVE);
    setSubmitting(true);
    setThreadError(null);
    try {
      const next = await submitQaClarification({
        clientRequestId: requestIdFor(`clarify|${sourceTurnId}|${selectedNodeId ?? "none"}`),
        conversationId: current.conversationId,
        sourceTurnId,
        selectedNodeId: selectedNodeId ?? undefined,
        noneOfTheAbove: selectedNodeId === null,
      });
      pending.current = null;
      setResult(next);
      setChoice(null);
      await refreshThread(current.conversationId);
      await loadConversations();
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    } finally {
      setSubmitting(false);
    }
  }

  function openPanel(kind: "list" | "inspector", trigger: HTMLButtonElement) {
    panelTrigger.current = trigger;
    setPanel(kind);
  }

  function toggleInspector(trigger: HTMLButtonElement) {
    if (panel === "inspector") {
      setPanel(null);
      return;
    }
    openPanel("inspector", trigger);
  }

  const listPane = (
    <ConversationListPane
      conversations={conversations}
      currentId={current?.conversationId ?? null}
      loading={listLoading}
      error={listError}
      nextCursor={listCursor}
      onNew={newConversation}
      onOpen={(conversationId) => void openConversation(conversationId)}
      onRetry={() => void loadConversations()}
      onLoadMore={() => void loadConversations(listCursor ?? undefined)}
    />
  );

  return (
    <section className="qa-page qa-chat" aria-labelledby="chat-title">
      <main className="qa-thread">
        <header className="qa-thread-heading">
          <div className="qa-thread-title">
            <button
              type="button"
              aria-expanded={panel === "list"}
              onClick={(event) => openPanel("list", event.currentTarget)}
            >
              对话列表
            </button>
            <div>
              <h1 id="chat-title">问答</h1>
              <h2>{current?.title ?? "新对话"}</h2>
            </div>
          </div>
          <div className="qa-thread-actions">
            <button
              type="button"
              aria-expanded={inspectorOpen}
              onClick={(event) => toggleInspector(event.currentTarget)}
            >
              本轮处理
            </button>
            {current ? (
              <div className="qa-menu">
                <button
                  type="button"
                  aria-expanded={menuOpen}
                  onClick={() => setMenuOpen((open) => !open)}
                >
                  对话操作
                </button>
                {menuOpen ? (
                  <div className="qa-menu-items" role="group" aria-label="对话操作菜单">
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false);
                        setDialog("rename");
                      }}
                    >
                      重命名
                    </button>
                    <button
                      className="danger"
                      type="button"
                      onClick={() => {
                        setMenuOpen(false);
                        setDialog("delete");
                      }}
                    >
                      删除
                    </button>
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        </header>

        <div className="qa-messages">
          {earlierCursor ? (
            <button className="qa-load-earlier" type="button" onClick={() => void loadEarlier()}>
              加载更早的消息
            </button>
          ) : null}

          {messages.length === 0 ? (
            <div className="qa-welcome">
              <h3>从你的知识库开始提问</h3>
              <p>我会结合对话历史理解问题；方向不明确时会先请你确认。</p>
              <p className="muted">例如：海外仓入库超时后怎么处理？ / 退货需要哪些材料？</p>
            </div>
          ) : (
            <ol className="qa-messages-list">
              {messages.map((message) => (
                <li className={`qa-message ${message.role.toLowerCase()}`} key={message.messageId}>
                  <span className="qa-message-role">
                    {message.role === "USER" ? "你" : "助手"}
                  </span>
                  <p className="qa-message-body">{message.content}</p>
                  <time dateTime={message.createdAt}>{relativeTime(message.createdAt)}</time>
                </li>
              ))}
            </ol>
          )}

          {notice ? (
            <p className="qa-system-notice" role="status">
              {notice}
            </p>
          ) : null}

          {active && !generation ? (
            <p className="qa-system-notice" role="status">
              正在理解问题…
            </p>
          ) : null}

          {generation ? (
            <article
              className="qa-live-answer"
              aria-busy={generationActive ? "true" : "false"}
            >
              <span className="qa-message-role">助手</span>
              {generation.answer ? <p className="qa-message-body">{generation.answer}</p> : null}
              <div className="qa-generation-status">
                <span>{generationStatusLabel(generation.phase)}</span>
                {generationActive && generation.taskId ? (
                  <button type="button" onClick={() => void stopGeneration()} aria-label="停止生成">
                    停止
                  </button>
                ) : null}
              </div>
              {generation.phase === "stopped" ? (
                <p role="status">
                  {generation.answer
                    ? "已停止生成，以上是已经产生的部分。"
                    : "已停止，本轮没有开始生成。"}
                </p>
              ) : null}
              {generation.saved === false && generation.answer ? (
                <p role="status">本条回答未能保存，刷新后将不可见</p>
              ) : null}
            </article>
          ) : null}

          {/*
            ui-spec §8：历史部分失败要在主区域给一次非阻塞提示。它不进 Inspector 就完事——
            "这次回答没参考近期上下文"是用户读结果时必须知道的事实，不是验收细节。
          */}
          {result?.turn.degradations.includes("HISTORY_UNAVAILABLE") ? (
            <p className="qa-system-notice">
              这一轮没能加载近期历史，只用摘要和当前问题做了理解。
            </p>
          ) : null}

          {/*
            来源摘要行是 C 布局下**来源的主入口**（ui-spec §13.3）：抽屉默认关着，绝大多数
            用户只会看到这一行，所以它必须自己说清三件事——有几条依据、精排了没有、结果全不全。
          */}
          {retrieval && retrieval.sources.length > 0 ? (
            <p className="qa-sources-summary">
              <button type="button" onClick={() => setPanel("inspector")}>
                依据 {retrieval.sources.length} 条来源 ›
              </button>
              {!retrieval.rerankApplied ? <RerankMark /> : null}
              {retrieval.partial ? <PartialMark /> : null}
            </p>
          ) : null}

          {/*
            部分知识库失败：非阻塞提示 + 点名失败库 + 两个动作（ui-spec §13.6）。判断依据是
            服务端的 partial，**不是 sources.length**——用条数倒推会把"库里本来就少"说成不完整。
          */}
          {retrieval?.partial && !partialDismissed ? (
            <div className="qa-system-notice" role="status">
              <p>
                {failedNames ? `「${failedNames}」这个知识库本轮没有查成，下面的结果不完整。`
                  : "部分知识库本轮没有查成，下面的结果不完整。"}
              </p>
              <div className="qa-notice-actions">
                <button type="button" onClick={() => void retryRetrieval()} disabled={retrying}>
                  {retrying ? "正在重试…" : "重试失败的知识库"}
                </button>
                <button type="button" className="secondary" onClick={() => setPartialDismissed(true)}>
                  就现有结果继续
                </button>
              </div>
            </div>
          ) : null}

          {/*
            无结果与 3A 的 NO_MATCH 必须可区分：一个是方向明确但库里没有，一个是方向不明。
            **不提供任何"用通用知识回答"的入口**（PRD §12.3）。
          */}
          {retrieval?.status === "NO_RESULT" ? (
            <div className="qa-system-notice" role="status">
              <p>没有在你的知识库里找到相关内容。</p>
              <ul className="qa-next-steps">
                <li>换个说法再问一次</li>
                <li>指定要查的知识库</li>
                <li>检查这份资料是否已入库</li>
              </ul>
            </div>
          ) : null}

          {retrieval?.status === "RETRIEVAL_FAILED" ? (
            <p className="qa-system-notice qa-notice-error" role="status">
              本轮检索没有查成，这与"查了但没有"不是一回事。可以重试失败的知识库。
            </p>
          ) : null}

          {/*
            确认卡在消息区，与 3A 的歧义引导卡同一位置——确认需要用户动作，藏进默认关闭的
            抽屉等于没有确认（ui-spec §13.1 不变量 2 / §13.7.2）。
          */}
          {pendingTool ? (
            <ToolConfirmCard
              tool={pendingTool}
              busy={toolBusy === pendingTool.toolCallId}
              onConfirm={() => void decideTool(pendingTool.toolCallId, "CONFIRM")}
              onCancel={() => void decideTool(pendingTool.toolCallId, "CANCEL")}
            />
          ) : null}

          {/*
            不可用不能静默跳过（ui-spec §13.7.3）：静默会让用户以为系统不支持这件事，
            而真相是配置缺失——这是用户无法自助的情况，所以只说明、不给动作。
          */}
          {unavailableTools.map((tool) => (
            <p className="qa-system-notice" role="status" key={tool.toolCallId}>
              系统判断需要调用「{tool.displayName ?? tool.toolName}」，但它不在当前可用工具清单中，本轮未执行。
            </p>
          ))}

          {/*
            参数抽取失败即中止，**禁止用默认值继续**（PRD §12.7）。界面上不存在
            "跳过这个参数继续"的路径，所以这里只有说明，没有按钮。
          */}
          {abortedTools.map((tool) => (
            <p className="qa-system-notice" role="status" key={tool.toolCallId}>
              「{tool.displayName ?? tool.toolName}」还缺必要信息，本轮未执行。请补充后再说一次。
            </p>
          ))}

          {/*
            失败给归一化文案 + 重试。有副作用的工具重试会重新出确认卡（服务端产生新的
            toolCallId），所以这里不需要额外提示"将再次确认"——卡片自己会出现。
          */}
          {tools
            .filter((tool) => tool.status === "FAILED")
            .map((tool) => (
              <div className="qa-system-notice" role="status" key={tool.toolCallId}>
                <p>
                  「{tool.displayName ?? tool.toolName}」执行失败：{toolFailureLabel(tool.failureCode)}。
                </p>
                <div className="qa-notice-actions">
                  <button
                    type="button"
                    disabled={toolBusy === tool.toolCallId}
                    onClick={() => void retryTool(tool.toolCallId)}
                  >
                    {toolBusy === tool.toolCallId ? "正在重试…" : "重试"}
                  </button>
                </div>
              </div>
            ))}

          {toolRunning ? (
            <p className="qa-system-notice" role="status" aria-live="polite">
              正在执行工具…
            </p>
          ) : null}

          {status === "ACTIONS_READY" ? (
            <p className="qa-system-notice">
              <strong className="qa-tag">{retrieval ? "3B 验收" : "3A 验收"}</strong>{" "}
              {retrieval ? RETRIEVAL_COMPLETION_NOTICE : COMPLETION_NOTICE}
            </p>
          ) : null}

          {status === "REJECTED_TOO_MANY_TOPICS" ? (
            <p className="qa-system-notice">
              这次包含超过三个主题。请拆成更小的问题后重新提问，我不会只回答其中一部分。
            </p>
          ) : null}

          {status === "FAILED" && !result?.generation ? (
            <p className="qa-system-notice qa-notice-error">
              本轮理解失败，原问题已保存，你可以重新发送。
            </p>
          ) : null}

          {status === "FAILED" && result?.generation ? (
            <div className="qa-system-notice qa-notice-error" role="status">
              <p>{generationFailureLabel(result.turn.terminalReason)}</p>
              {lastQuestion ? (
                <div className="qa-notice-actions">
                  <button type="button" onClick={() => void sendQuestion(lastQuestion)}>重试</button>
                </div>
              ) : null}
            </div>
          ) : null}

          {status === "STOPPED" && !generation ? (
            <p className="qa-system-notice" role="status">
              {result?.assistantMessage
                ? "已停止生成，以上是已经产生的部分。"
                : "已停止，本轮没有开始生成。"}
            </p>
          ) : null}

          {status === "TIMED_OUT" ? (
            <div className="qa-system-notice" role="status">
              <p>当前排队较多，本轮未开始生成</p>
              {lastQuestion ? (
                <div className="qa-notice-actions">
                  <button type="button" onClick={() => void sendQuestion(lastQuestion)}>重试</button>
                </div>
              ) : null}
            </div>
          ) : null}

          {result?.clarification ? (
            <fieldset className="qa-clarification" disabled={submitting}>
              <legend>请选择最接近的方向</legend>
              {result.clarification.options.map((option) => (
                <label key={option.nodeId}>
                  <input
                    type="radio"
                    name="qa-clarification"
                    checked={choice === option.nodeId}
                    onChange={() => void chooseClarification(option.nodeId)}
                  />
                  <span>{option.label}</span>
                </label>
              ))}
              {result.clarification.allowNoneOfTheAbove ? (
                <label>
                  <input
                    type="radio"
                    name="qa-clarification"
                    checked={choice === NONE_OF_THE_ABOVE}
                    onChange={() => void chooseClarification(null)}
                  />
                  <span>都不是这些</span>
                </label>
              ) : null}
              {threadError && choice ? (
                <button
                  type="button"
                  onClick={() =>
                    void chooseClarification(choice === NONE_OF_THE_ABOVE ? null : choice)
                  }
                >
                  重试这个方向
                </button>
              ) : null}
            </fieldset>
          ) : null}
        </div>

        {threadError ? (
          <p className="qa-inline-error" role="alert">
            <strong>{threadError.title}</strong>
            {threadError.detail ? <span> {threadError.detail}</span> : null}
          </p>
        ) : null}

        <div className="qa-composer">
          <textarea
            ref={editor}
            value={draft}
            disabled={composerLocked}
            maxLength={20000}
            placeholder="输入你的问题…"
            aria-label="输入你的问题"
            onChange={(event) =>
              setDrafts((prev) => ({ ...prev, [draftKey]: event.target.value }))
            }
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void sendQuestion();
              }
            }}
          />
          <button
            className="primary"
            type="button"
            disabled={composerLocked || !draft.trim()}
            onClick={() => void sendQuestion()}
          >
            {submitting ? "发送中…" : "发送"}
          </button>
          <small>
            {active ? "当前本轮正在处理，完成后可继续提问" : "Enter 发送 · Shift+Enter 换行"}
            {" · "}
            {draft.length} / 20000
          </small>
        </div>
      </main>

      {panel === "list" ? (
        <Drawer title="对话列表" side="left" onClose={() => setPanel(null)}>
          {listPane}
        </Drawer>
      ) : null}

      {panel === "inspector" ? (
        <Drawer title="本轮处理" onClose={() => setPanel(null)}>
          <TurnResultPane result={result} />
        </Drawer>
      ) : null}

      {dialog === "rename" && current ? (
        <RenameConversationDialog
          conversation={current}
          onCancel={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            void openConversation(current.conversationId);
            void loadConversations();
          }}
        />
      ) : null}

      {dialog === "delete" && current ? (
        <DeleteConversationDialog
          conversation={current}
          onCancel={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            newConversation();
            setNotice("对话已删除，它的消息已从列表隐藏。");
            void loadConversations();
          }}
        />
      ) : null}
    </section>
  );
}

function isLiveTerminal(phase: LiveGeneration["phase"]) {
  return phase === "completed" || phase === "stopped" || phase === "failed" || phase === "timed_out";
}

function generationPhase(status: "STOPPED" | "COMPLETED" | "FAILED" | "TIMED_OUT"): LiveGeneration["phase"] {
  return status.toLowerCase() as LiveGeneration["phase"];
}

function generationStatusLabel(phase: LiveGeneration["phase"]) {
  switch (phase) {
    case "submitting": return "正在保存…";
    case "queued": return "排队中…";
    case "generating": return "正在生成…";
    case "recovering": return "这一轮仍在进行，完成后会显示在这里";
    case "completed": return "回答已完成";
    case "stopped": return "已停止";
    case "failed": return "生成失败，请重试";
    case "timed_out": return "当前排队较多，本轮未开始生成";
  }
}

function isActiveTurn(status: TurnResult["turn"]["status"]) {
  return status === "QUEUED" || status === "GENERATING";
}

function recoveringGeneration(conversationId: string, result: TurnResult): LiveGeneration {
  return {
    phase: "recovering",
    conversationId,
    turnId: result.turn.turnId,
    taskId: result.generation?.taskId ?? null,
    answer: "",
    saved: null,
    failureCode: null,
  };
}

function generationFailureLabel(reason: string | null) {
  switch (reason) {
    case "MODEL_UNAVAILABLE": return "回答服务暂时不可用，请稍后重试。";
    case "FIRST_BYTE_TIMEOUT": return "回答服务响应超时，请重试。";
    case "GENERATION_TIMEOUT": return "回答生成超时，请重试。";
    case "SERVICE_RESTARTED":
    case "SERVICE_SHUTDOWN": return "服务重启中断了本轮回答，请重试。";
    default: return "回答生成失败，请重试。";
  }
}
