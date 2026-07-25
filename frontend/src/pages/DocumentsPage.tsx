import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import {
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
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";

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
                      <td colSpan={5}>
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
