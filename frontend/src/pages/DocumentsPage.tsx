import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import {
  DOC_DELETED_MESSAGE,
  DOC_TOGGLED_MESSAGE,
  documents,
  ingestion,
  PHASE_RUNNING_LABEL,
  RUNNING_TOOLTIP,
  STATUS_LABEL,
  TRIGGER_LABEL,
  type DocumentStatus,
  type SourceDocument,
} from "../api/documents";
import { useDocumentPolling } from "../hooks/useDocumentPolling";
import { RunHistory } from "../components/RunHistory";
import { knowledgeBases, type KnowledgeBase } from "../api/knowledgeBases";
import { toUserMessage } from "../api/errors";
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";
import { Modal } from "../components/Modal";
import { Toast } from "../components/Toast";

/**
 * 只有「禁用」和「删除」需要二次确认（ui-spec §9）：两者都点明代价，前者是"重新启用要
 * 重算向量"，后者是"连带删掉 N 个分块且界面上无法恢复"。**启用不需要确认**——它把内容放
 * 回检索，是恢复性动作。
 */
type Dialog =
  | { kind: "disable"; target: SourceDocument }
  | { kind: "delete"; target: SourceDocument }
  | null;

const FILTERS: Array<{ value: DocumentStatus | ""; label: string }> = [
  { value: "", label: "全部" },
  { value: "PENDING", label: "待处理" },
  { value: "RUNNING", label: "处理中" },
  { value: "SUCCESS", label: "已完成" },
  { value: "FAILED", label: "处理失败" },
];

export function DocumentsPage() {
  const kbId = Number(useParams().kbId);
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [items, setItems] = useState<SourceDocument[] | null>(null);
  const [filter, setFilter] = useState<DocumentStatus | "">("");
  const [error, setError] = useState<unknown>(null);
  const [uploadError, setUploadError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [toast, setToast] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    setError(null);
    Promise.all([
      knowledgeBases.list(1, 100).then((page) => page.items.find((k) => k.id === kbId) ?? null),
      documents.list(kbId, filter || undefined, 1, 100),
    ])
      .then(([foundKb, page]) => {
        setKb(foundKb);
        setItems(page.items);
      })
      .catch((cause: unknown) => {
        setItems(null);
        setError(cause);
      });
  }, [kbId, filter]);

  useEffect(load, [load]);

  const dismissToast = useCallback(() => setToast(null), []);

  const runningIds = (items ?? []).filter((d) => d.status === "RUNNING").map((d) => d.id);
  const progress = useDocumentPolling(runningIds, load);

  const onTrigger = (docId: number) => {
    setUploadError(null);
    ingestion
      .trigger(docId)
      .then(() => {
        setRefreshKey((n) => n + 1);
        load();
      })
      .catch((cause: unknown) => setUploadError(cause));
  };

  // 启用是恢复性动作，不做二次确认，直接调（ui-spec §9 的确认清单里只有禁用和删除）
  const onEnable = (doc: SourceDocument) => {
    setUploadError(null);
    documents
      .setEnabled(doc.id, true)
      .then(() => {
        setToast(DOC_TOGGLED_MESSAGE.true);
        load();
      })
      .catch((cause: unknown) => setUploadError(cause));
  };

  const closeDialogAndReload = (message: string) => {
    setDialog(null);
    setToast(message);
    load();
  };

  const onPick = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    setUploading(true);
    setUploadError(null);
    documents
      .uploadFile(kbId, file)
      .then(load)
      .catch((cause: unknown) => setUploadError(cause))
      .finally(() => {
        setUploading(false);
        // 清空 input，否则连续选同一个文件不会再触发 change
        if (fileInput.current) {
          fileInput.current.value = "";
        }
      });
  };

  const uploadButton = (
    <>
      <input
        ref={fileInput}
        type="file"
        accept=".txt,.md,.pdf,.docx"
        onChange={onPick}
        hidden
      />
      <button
        type="button"
        className="primary"
        disabled={uploading}
        onClick={() => fileInput.current?.click()}
      >
        {uploading ? "上传中…" : "上传文件"}
      </button>
    </>
  );

  return (
    <section>
      <nav className="breadcrumb">
        <Link to="/knowledge-bases">知识库</Link>
        <span>/</span>
        <span>{kb?.name ?? "…"}</span>
      </nav>

      <header className="page-header">
        <h1>文档</h1>
        {items && items.length > 0 ? uploadButton : null}
      </header>

      {uploadError ? <ErrorState error={uploadError} /> : null}
      {error ? <ErrorState error={error} onRetry={load} /> : null}
      {!error && items === null ? <Loading /> : null}

      {items !== null && !error ? (
        <div className="filter-bar">
          {FILTERS.map((option) => (
            <button
              key={option.value || "all"}
              type="button"
              className={filter === option.value ? "chip active" : "chip"}
              onClick={() => setFilter(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      ) : null}

      {/* 筛选后为空不是空态：显示"当前筛选条件下没有内容"，做成空态会让用户以为数据没了（ui-spec §8） */}
      {items?.length === 0 && filter !== "" ? (
        <p className="muted">
          当前筛选条件下没有内容。{" "}
          <button type="button" className="link" onClick={() => setFilter("")}>
            清除筛选
          </button>
        </p>
      ) : null}

      {items?.length === 0 && filter === "" ? (
        <div className="empty-state">
          <p className="state-title">这个知识库还没有文档。</p>
          <p className="state-detail">上传本地文件，或添加一个可定时同步的文件链接。</p>
          <div className="empty-actions">{uploadButton}</div>
        </div>
      ) : null}

      {items && items.length > 0 ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>状态</th>
              <th>检索</th>
              <th className="numeric">大小</th>
              <th className="numeric">分块</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((doc) => {
              const phase = progress[doc.id]?.latestRun?.phase ?? null;
              const isRunning = doc.status === "RUNNING";
              return (
                <Fragment key={doc.id}>
                  <tr>
                    <td>{doc.name}</td>
                    <td>
                      <span className={`status status-${doc.status.toLowerCase()}`}>
                        {STATUS_LABEL[doc.status]}
                      </span>
                      {/* 处理中显示"正在做什么"，比一个转圈图标信息量大得多 */}
                      {isRunning && phase ? (
                        <span className="phase-hint">{PHASE_RUNNING_LABEL[phase]}</span>
                      ) : null}
                    </td>
                    <td>
                      {/* 启用状态与处理状态是两个正交的维度，混在一列里会让"已完成但不参与
                          检索"这种组合说不清楚 */}
                      <span
                        className={doc.enabled ? "tag tag-on" : "tag tag-off"}
                        title={doc.enabled ? "参与检索" : "不参与检索，向量已清理"}
                      >
                        {doc.enabled ? "已启用" : "已禁用"}
                      </span>
                    </td>
                    <td className="numeric">{formatSize(doc.fileSize)}</td>
                    <td className="numeric">{doc.chunkCount}</td>
                    <td className="actions">
                      <button
                        type="button"
                        className={doc.status === "PENDING" ? "primary" : ""}
                        disabled={isRunning}
                        title={isRunning ? RUNNING_TOOLTIP : undefined}
                        onClick={() => onTrigger(doc.id)}
                      >
                        {TRIGGER_LABEL[doc.status]}
                      </button>
                      <Link className="button-link" to={`/knowledge-bases/${kbId}/documents/${doc.id}/chunks`}>
                        分块
                      </Link>
                      <button
                        type="button"
                        disabled={isRunning}
                        title={isRunning ? RUNNING_TOOLTIP : undefined}
                        onClick={() =>
                          doc.enabled ? setDialog({ kind: "disable", target: doc }) : onEnable(doc)
                        }
                      >
                        {doc.enabled ? "禁用" : "启用"}
                      </button>
                      <button
                        type="button"
                        className="danger"
                        disabled={isRunning}
                        title={isRunning ? RUNNING_TOOLTIP : undefined}
                        onClick={() => setDialog({ kind: "delete", target: doc })}
                      >
                        删除
                      </button>
                      <button
                        type="button"
                        onClick={() => setExpanded(expanded === doc.id ? null : doc.id)}
                      >
                        {expanded === doc.id ? "收起记录" : "处理记录"}
                      </button>
                    </td>
                  </tr>
                  {expanded === doc.id ? (
                    <tr className="detail-row">
                      <td colSpan={6}>
                        <RunHistory docId={doc.id} refreshKey={refreshKey} />
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      ) : null}

      {dialog?.kind === "disable" ? (
        <ConfirmDialog
          title={`禁用「${dialog.target.name}」`}
          confirmLabel="确认禁用"
          action={() => documents.setEnabled(dialog.target.id, false)}
          onCancel={() => setDialog(null)}
          onDone={() => closeDialogAndReload(DOC_TOGGLED_MESSAGE.false)}
        >
          {/* ui-spec §9：点明代价——重新启用要重算向量，也就是会再花一次模型调用的钱 */}
          <p>该文档全部内容将不参与检索。重新启用时需要重新计算向量。</p>
        </ConfirmDialog>
      ) : null}

      {dialog?.kind === "delete" ? (
        <ConfirmDialog
          title={`删除「${dialog.target.name}」`}
          confirmLabel="确认删除"
          danger
          action={() => documents.remove(dialog.target.id)}
          onCancel={() => setDialog(null)}
          onDone={() => closeDialogAndReload(DOC_DELETED_MESSAGE)}
        >
          {/*
            "无法在界面上恢复"这个措辞是准确的：数据确实还在库里（逻辑删除），写"永久删除"
            是撒谎，写"可以恢复"会让用户以为界面上有恢复入口（ui-spec §9）。
          */}
          <p>
            将同时删除该文档的 <strong>{dialog.target.chunkCount}</strong> 个分块，且无法在界面上恢复。
          </p>
        </ConfirmDialog>
      ) : null}

      {toast ? <Toast message={toast} onDismiss={dismissToast} /> : null}

      {items?.some((doc) => doc.status === "PENDING") ? (
        // ui-spec 把 PENDING 当重点：上传接口返回后文档并没有被分块，只显示一个灰标签
        // 用户会以为系统在后台自动处理，然后一直等下去。
        <p className="hint">
          有 {items.filter((doc) => doc.status === "PENDING").length} 篇文档待处理。处理后才会切分成分块并写入向量库。
        </p>
      ) : null}
    </section>
  );
}

/**
 * 二次确认弹层。文案由调用方以 children 传入——ui-spec §9 要求每种操作点明**自己的**代价，
 * 抽成一句通用的"确定要继续吗"就把这份规格作废了。
 */
function ConfirmDialog({
  title,
  confirmLabel,
  danger,
  action,
  onCancel,
  onDone,
  children,
}: {
  title: string;
  confirmLabel: string;
  danger?: boolean;
  action: () => Promise<unknown>;
  onCancel: () => void;
  onDone: () => void;
  children: React.ReactNode;
}) {
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const onConfirm = () => {
    setBusy(true);
    setError(null);
    action()
      .then(onDone)
      .catch((cause: unknown) => setError(cause))
      .finally(() => setBusy(false));
  };

  return (
    <Modal
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button
            type="button"
            className={danger ? "danger" : "primary"}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? "处理中…" : confirmLabel}
          </button>
        </>
      }
    >
      {children}
      {/* 409 这类错误提交后才知道，提示要留在弹层里 */}
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}

function formatSize(bytes: number | null): string {
  if (bytes === null) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
