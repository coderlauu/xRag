import { useState } from "react";
import { Modal } from "../../components/Modal";
import {
  deleteQaConversation,
  renameQaConversation,
  toQaUserMessage,
  type ConversationDetail,
} from "../../api/qa";
import type { UserFacingMessage } from "../../api/errors";

/**
 * 重命名弹层（ui-spec §4.1）。
 *
 * 失败**不关闭弹层也不回填服务端标题**：409 的正确处置是让用户比较自己刚输入的标题和
 * 别处已经改成的标题，自动覆盖任何一边都是替用户做决定。
 */
export function RenameConversationDialog({
  conversation,
  onCancel,
  onDone,
}: {
  conversation: ConversationDetail;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [title, setTitle] = useState(conversation.title);
  const [error, setError] = useState<UserFacingMessage | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = () => {
    const next = title.trim();
    if (!next || busy) return;
    setBusy(true);
    setError(null);
    renameQaConversation(conversation.conversationId, next, conversation.version)
      .then(onDone)
      .catch((cause: unknown) => setError(toQaUserMessage(cause)))
      .finally(() => setBusy(false));
  };

  return (
    <Modal
      title="重命名对话"
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button className="primary" type="button" onClick={submit} disabled={busy || !title.trim()}>
            {busy ? "保存中…" : "保存"}
          </button>
        </>
      }
    >
      <label>
        <span>对话标题</span>
        <input
          value={title}
          maxLength={200}
          aria-label="对话标题"
          onChange={(event) => setTitle(event.target.value)}
        />
      </label>
      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error.title}</strong>
          {error.detail ? <span> {error.detail}</span> : null}
        </p>
      ) : null}
    </Modal>
  );
}

/** 删除二次确认。文案必须说清"隐藏"而不是"抹掉"——3A 全系统是逻辑删除。 */
export function DeleteConversationDialog({
  conversation,
  onCancel,
  onDone,
}: {
  conversation: ConversationDetail;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [error, setError] = useState<UserFacingMessage | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    deleteQaConversation(conversation.conversationId, conversation.version)
      .then(onDone)
      .catch((cause: unknown) => setError(toQaUserMessage(cause)))
      .finally(() => setBusy(false));
  };

  return (
    <Modal
      title={`删除「${conversation.title}」`}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button className="danger" type="button" onClick={submit} disabled={busy}>
            {busy ? "处理中…" : "确认删除"}
          </button>
        </>
      }
    >
      <p>删除后，这个对话的消息将从列表隐藏，且无法在界面上恢复。</p>
      <p className="muted">本轮仍在处理时无法删除，需要等它到达稳定终态。</p>
      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error.title}</strong>
          {error.detail ? <span> {error.detail}</span> : null}
        </p>
      ) : null}
    </Modal>
  );
}
