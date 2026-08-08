import { useEffect, useRef, useState } from "react";
import {
  listQaConversations,
  listQaMessages,
  readQaConversation,
  readQaTurn,
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
import { TurnInspectorPane } from "./chat/TurnInspectorPane";
import { useViewportLayout } from "../hooks/useLayout";
import { relativeTime } from "./chat/labels";

const COMPLETION_NOTICE =
  "已完成问题理解并形成后续检索动作。当前里程碑尚未执行知识库检索或生成答案。";

/** 新对话在草稿表里的键。用空串而不是 null，省掉一层 `Map | null` 的分支。 */
const NEW_CONVERSATION = "";

/** 单选组里"都不是这些"的本地标记值，不会作为 nodeId 发给服务端。 */
const NONE_OF_THE_ABOVE = "__none_of_the_above__";

/**
 * 问答页（approved A — Conversation + Inspector）。
 *
 * 三条不能松的纪律：
 *
 * 1. **只展示真实 3A 状态**——没有伪答案、伪来源、停止或排队控件，3A 完成提示带
 *    `3A 验收` 标签且不进入历史助手消息。
 * 2. **同一次提交重试复用同一个 clientRequestId**，但正文一变就必须换新的：后端对
 *    "同 key 不同正文"返回 409 `IDEMPOTENCY_KEY_REUSED`，复用到底反而把用户卡死。
 * 3. **服务端是终态的唯一事实来源**——本地不推断"应该成功了"，一律以返回的 turn 为准。
 */
export function ChatPage() {
  const layout = useViewportLayout();

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

  const [menuOpen, setMenuOpen] = useState(false);
  const [dialog, setDialog] = useState<"rename" | "delete" | null>(null);
  const [panel, setPanel] = useState<"list" | "inspector" | null>(null);
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false);

  const pending = useRef<{ signature: string; requestId: string } | null>(null);
  const panelTrigger = useRef<HTMLButtonElement | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);

  const draftKey = current?.conversationId ?? NEW_CONVERSATION;
  const draft = drafts[draftKey] ?? "";
  const status = result?.turn.status;
  const active = submitting || status === "SAVED" || status === "PROCESSING";
  const inspectorOpen = layout === "wide" ? !inspectorCollapsed : panel === "inspector";

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
    if (layout === "wide") {
      setInspectorCollapsed((collapsed) => !collapsed);
      return;
    }
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
    <section
      className={`qa-page qa-chat qa-layout-${layout}${
        layout === "wide" && inspectorOpen ? " qa-inspector-docked" : ""
      }`}
      aria-labelledby="chat-title"
    >
      {layout === "narrow" ? null : (
        <aside className="qa-conversations" aria-label="对话列表">
          {listPane}
        </aside>
      )}

      <main className="qa-thread">
        <header className="qa-thread-heading">
          <div className="qa-thread-title">
            {layout === "narrow" ? (
              <button
                type="button"
                onClick={(event) => openPanel("list", event.currentTarget)}
              >
                对话列表
              </button>
            ) : null}
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

          {status === "ACTIONS_READY" ? (
            <p className="qa-system-notice">
              <strong className="qa-tag">3A 验收</strong> {COMPLETION_NOTICE}
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

      {layout === "wide" && inspectorOpen ? (
        <aside className="qa-inspector" aria-label="本轮处理">
          <TurnInspectorPane result={result} />
        </aside>
      ) : null}

      {panel === "list" ? (
        <Drawer title="对话列表" onClose={() => setPanel(null)}>
          {listPane}
        </Drawer>
      ) : null}

      {panel === "inspector" ? (
        <Drawer title="本轮处理" onClose={() => setPanel(null)}>
          <TurnInspectorPane result={result} />
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
