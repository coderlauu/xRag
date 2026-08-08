import type { ConversationListItem } from "../../api/qa";
import { relativeTime } from "./labels";

interface ConversationListPaneProps {
  conversations: ConversationListItem[];
  currentId: string | null;
  loading: boolean;
  error: string | null;
  nextCursor: string | null;
  onNew: () => void;
  onOpen: (conversationId: string) => void;
  onRetry: () => void;
  onLoadMore: () => void;
}

/**
 * 对话列表（ui-spec §4.1）。它不含自己的标题：桌面端由外层 `<aside aria-label>` 命名，
 * 窄屏由抽屉标题命名，两处再各写一个 heading 只会让屏幕阅读器听两遍同一个词。
 */
export function ConversationListPane({
  conversations,
  currentId,
  loading,
  error,
  nextCursor,
  onNew,
  onOpen,
  onRetry,
  onLoadMore,
}: ConversationListPaneProps) {
  return (
    <div className="qa-list-pane">
      <button className="primary qa-new-conversation" type="button" onClick={onNew}>
        新对话
      </button>

      {/* 骨架是中性方块而不是假标题：假标题会被当成"已经有这几个对话"。 */}
      {loading && conversations.length === 0 ? (
        <div className="qa-skeleton" aria-hidden="true">
          {[0, 1, 2, 3, 4, 5].map((row) => (
            <span key={row} />
          ))}
        </div>
      ) : null}

      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error}</strong>
          <button type="button" onClick={onRetry}>
            重试
          </button>
        </p>
      ) : null}

      {!loading && !error && conversations.length === 0 ? (
        <div className="qa-empty">
          <strong>还没有对话</strong>
          <span>发出第一个问题后会自动保存。</span>
        </div>
      ) : null}

      <nav aria-label="最近对话">
        {conversations.map((conversation) => (
          <button
            className={conversation.conversationId === currentId ? "selected" : ""}
            key={conversation.conversationId}
            type="button"
            aria-current={conversation.conversationId === currentId ? "true" : undefined}
            onClick={() => onOpen(conversation.conversationId)}
          >
            <strong>{conversation.title}</strong>
            <span className="qa-list-preview">{conversation.messagePreview}</span>
            <small>
              {relativeTime(conversation.lastActiveAt)}
              {conversation.activeTurn ? " · 处理中" : ""}
            </small>
          </button>
        ))}
      </nav>

      {nextCursor ? (
        <button className="qa-load-more" type="button" onClick={onLoadMore}>
          加载更多对话
        </button>
      ) : null}
    </div>
  );
}
