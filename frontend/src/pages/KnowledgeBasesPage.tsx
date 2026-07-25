import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { knowledgeBases, type KnowledgeBase } from "../api/knowledgeBases";
import { toUserMessage } from "../api/errors";
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";
import { Modal } from "../components/Modal";

type Dialog =
  | { kind: "create" }
  | { kind: "rename"; target: KnowledgeBase }
  | { kind: "delete"; target: KnowledgeBase }
  | null;

export function KnowledgeBasesPage() {
  const [items, setItems] = useState<KnowledgeBase[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [dialog, setDialog] = useState<Dialog>(null);

  const load = useCallback(() => {
    setError(null);
    knowledgeBases
      .list(1, 100)
      .then((page) => setItems(page.items))
      .catch((cause: unknown) => {
        setItems(null);
        setError(cause);
      });
  }, []);

  useEffect(load, [load]);

  const closeAndReload = () => {
    setDialog(null);
    load();
  };

  return (
    <section>
      <header className="page-header">
        <h1>知识库</h1>
        {items && items.length > 0 ? (
          <button type="button" className="primary" onClick={() => setDialog({ kind: "create" })}>
            创建知识库
          </button>
        ) : null}
      </header>

      {error ? <ErrorState error={error} onRetry={load} /> : null}
      {!error && items === null ? <Loading /> : null}

      {items?.length === 0 ? (
        // 空态文案逐字取自 ui-spec.md §8：说明知识库是什么，而不是只说"暂无数据"。
        <div className="empty-state">
          <p className="state-title">还没有知识库。</p>
          <p className="state-detail">知识库是文档的归属边界，同一业务范围的资料放在一个知识库里。</p>
          <button type="button" className="primary" onClick={() => setDialog({ kind: "create" })}>
            创建知识库
          </button>
        </div>
      ) : null}

      {items && items.length > 0 ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th className="numeric">文档</th>
              <th className="numeric">分块</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((kb) => (
              <tr key={kb.id}>
                <td>
                  <Link to={`/knowledge-bases/${kb.id}`}>{kb.name}</Link>
                </td>
                <td className="muted">{kb.description || "—"}</td>
                <td className="numeric">{kb.documentCount}</td>
                <td className="numeric">{kb.chunkCount}</td>
                <td className="actions">
                  <button type="button" onClick={() => setDialog({ kind: "rename", target: kb })}>
                    重命名
                  </button>
                  <button
                    type="button"
                    className="danger"
                    onClick={() => setDialog({ kind: "delete", target: kb })}
                  >
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {dialog?.kind === "create" ? (
        <EditDialog title="创建知识库" submitLabel="创建" onCancel={() => setDialog(null)} onDone={closeAndReload}
          submit={(name, description) => knowledgeBases.create(name, description)} />
      ) : null}

      {dialog?.kind === "rename" ? (
        <EditDialog
          title="重命名知识库"
          submitLabel="保存"
          initialName={dialog.target.name}
          initialDescription={dialog.target.description ?? ""}
          onCancel={() => setDialog(null)}
          onDone={closeAndReload}
          submit={(name, description) => knowledgeBases.rename(dialog.target.id, name, description)}
        />
      ) : null}

      {dialog?.kind === "delete" ? (
        <DeleteDialog target={dialog.target} onCancel={() => setDialog(null)} onDone={closeAndReload} />
      ) : null}
    </section>
  );
}

interface EditDialogProps {
  title: string;
  submitLabel: string;
  initialName?: string;
  initialDescription?: string;
  submit: (name: string, description: string | undefined) => Promise<unknown>;
  onCancel: () => void;
  onDone: () => void;
}

function EditDialog({
  title,
  submitLabel,
  initialName = "",
  initialDescription = "",
  submit,
  onCancel,
  onDone,
}: EditDialogProps) {
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  const onSubmit = () => {
    setSaving(true);
    setError(null);
    submit(name, description || undefined)
      .then(onDone)
      .catch((cause: unknown) => setError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={saving}>
            取消
          </button>
          <button type="button" className="primary" onClick={onSubmit} disabled={saving || !name.trim()}>
            {saving ? "处理中…" : submitLabel}
          </button>
        </>
      }
    >
      <label>
        名称
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={128} autoFocus />
      </label>
      <label>
        描述（可选）
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1024} rows={3} />
      </label>
      {/* 重名这类错误是提交后才知道的，提示必须留在弹层里——关掉弹层再提示，用户输的内容就没了 */}
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}

function DeleteDialog({
  target,
  onCancel,
  onDone,
}: {
  target: KnowledgeBase;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [error, setError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState(false);

  const onConfirm = () => {
    setDeleting(true);
    setError(null);
    knowledgeBases
      .remove(target.id)
      .then(onDone)
      .catch((cause: unknown) => setError(cause))
      .finally(() => setDeleting(false));
  };

  return (
    <Modal
      title={`删除「${target.name}」`}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={deleting}>
            取消
          </button>
          <button type="button" className="danger" onClick={onConfirm} disabled={deleting}>
            {deleting ? "删除中…" : "确认删除"}
          </button>
        </>
      }
    >
      {/*
        ui-spec §9：必须点明连带影响和不可恢复。措辞是"无法在界面上恢复"而不是"永久删除"——
        数据确实还在库里（逻辑删除），说"永久删除"是撒谎，说"可以恢复"会让用户以为界面上有恢复入口。
      */}
      <p>
        将同时删除其中 <strong>{target.documentCount}</strong> 篇文档和{" "}
        <strong>{target.chunkCount}</strong> 个分块，且无法在界面上恢复。
      </p>
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}
