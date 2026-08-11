import { useEffect, useRef, useState } from "react";
import {
  listQaConversations,
  listQaMessages,
  readQaConversation,
  readQaTurn,
  retryQaRetrieval,
  submitQaClarification,
  submitQaQuestion,
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
import { relativeTime } from "./chat/labels";

const COMPLETION_NOTICE =
  "已完成问题理解并形成后续检索动作。当前里程碑尚未执行知识库检索或生成答案。";

/** 进过 3B 的轮次改用这句（ui-spec §13.5）。两句都带阶段标签，正式交付后一起删除。 */
const RETRIEVAL_COMPLETION_NOTICE = "已完成知识库检索。当前里程碑尚未生成答案。";

/** 新对话在草稿表里的键。用空串而不是 null，省掉一层 `Map | null` 的分支。 */
const NEW_CONVERSATION = "";

/** 单选组里"都不是这些"的本地标记值，不会作为 nodeId 发给服务端。 */
const NONE_OF_THE_ABOVE = "__none_of_the_above__";

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
  const [choice, setChoice] = useState<string | null>(null);

  const [retrying, setRetrying] = useState(false);
  /**
   * `就现有结果继续` 只收起提示，**不改服务端状态**——用户说的是"我知道了、别再挡我"，
   * 不是"把这轮标记成完整的"。partial 仍然是 true，来源区的 `结果不完整` 标记照旧。
   */
  const [partialDismissed, setPartialDismissed] = useState(false);

  const [menuOpen, setMenuOpen] = useState(false);
  const [dialog, setDialog] = useState<"rename" | "delete" | null>(null);
  const [panel, setPanel] = useState<"list" | "inspector" | null>(null);

  const pending = useRef<{ signature: string; requestId: string } | null>(null);
  const panelTrigger = useRef<HTMLButtonElement | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);

  const draftKey = current?.conversationId ?? NEW_CONVERSATION;
  const draft = drafts[draftKey] ?? "";
  const status = result?.turn.status;
  const active = submitting || status === "SAVED" || status === "PROCESSING";
  const retrieval = result?.retrieval ?? null;
  /**
   * 失败库名拼给提示文案。**只取库名，不取 failureCode 的原始值**——消息区那句是给用户看的，
   * 归一化原因留在抽屉的失败项列表里（ui-spec §13.6）。
   */
  const failedNames = (retrieval?.failedTargets ?? [])
    .map((target) => target.knowledgeBaseName)
    .filter((name): name is string => Boolean(name))
    .join("」「");
  const inspectorOpen = panel === "inspector";

  useEffect(() => {
    void loadConversations();
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
    setEarlierCursor(page.previousCursor);
    return detail;
  }

  async function openConversation(conversationId: string) {
    setThreadError(null);
    setNotice(null);
    setChoice(null);
    setPanel(null);
    try {
      const detail = await refreshThread(conversationId);
      setResult(
        detail.latestTurn ? await readQaTurn(conversationId, detail.latestTurn.turnId) : null,
      );
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
    setCurrent(null);
    setMessages([]);
    setEarlierCursor(null);
    setResult(null);
    setThreadError(null);
    setNotice(null);
    setChoice(null);
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

  async function sendQuestion() {
    const text = draft.trim();
    if (!text || active) return;
    const conversationId = current?.conversationId ?? null;
    const key = draftKey;
    setSubmitting(true);
    setThreadError(null);
    setNotice(null);
    try {
      const next = await submitQaQuestion({
        clientRequestId: requestIdFor(`question|${conversationId ?? "new"}|${text}`),
        conversationId,
        text,
      });
      pending.current = null;
      setResult(next);
      setChoice(null);
      setDrafts((prev) => ({ ...prev, [key]: "" }));
      const settled = next.conversation?.conversationId ?? conversationId;
      if (settled) {
        await refreshThread(settled);
      }
      await loadConversations();
    } catch (cause) {
      setThreadError(toQaUserMessage(cause));
    } finally {
      setSubmitting(false);
      editor.current?.focus();
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

        <div className="qa-messages" aria-live="polite">
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

          {active ? (
            <p className="qa-system-notice" role="status">
              正在理解问题…
            </p>
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

          {status === "FAILED" ? (
            <p className="qa-system-notice qa-notice-error">
              本轮理解失败，原问题已保存，你可以重新发送。
            </p>
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
            disabled={active}
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
            disabled={active || !draft.trim()}
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
