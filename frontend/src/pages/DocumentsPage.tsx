import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import {
  DOC_DELETED_MESSAGE,
  DOC_TOGGLED_MESSAGE,
  documents,
  FORM_ERRORS,
  ingestion,
  PHASE_RUNNING_LABEL,
  RUNNING_TOOLTIP,
  SAVED_MESSAGE,
  STATUS_LABEL,
  STRATEGY_HINT,
  STRATEGY_LABEL,
  TRIGGER_LABEL,
  URL_ADDED_MESSAGE,
  type ChunkStrategy,
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
  | { kind: "edit"; target: SourceDocument }
  | { kind: "addUrl" }
  /** 改了分块参数之后的后续确认（ui-spec §11）——不是 toast，因为它要用户做一个决定。 */
  | { kind: "rechunk"; target: SourceDocument }
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
        {items && items.length > 0 ? (
          <div className="empty-actions">
            {uploadButton}
            <button type="button" onClick={() => setDialog({ kind: "addUrl" })}>
              添加链接
            </button>
          </div>
        ) : null}
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
          <div className="empty-actions">
            {uploadButton}
            <button type="button" onClick={() => setDialog({ kind: "addUrl" })}>
              添加链接
            </button>
          </div>
        </div>
      ) : null}

      {items && items.length > 0 ? (
        <div className="table-scroll">
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
                      {/*
                        源文件是只读操作：`RUNNING` 与禁用都不拦（ui-spec §13）。文档正在
                        重新处理时，恰恰是用户最想看看原文的时候。
                      */}
                      <a
                        className="button-link"
                        href={documents.fileUrl(doc.id)}
                        target="_blank"
                        rel="noreferrer"
                      >
                        源文件
                      </a>
                      <button
                        type="button"
                        disabled={isRunning}
                        title={isRunning ? RUNNING_TOOLTIP : undefined}
                        onClick={() => setDialog({ kind: "edit", target: doc })}
                      >
                        编辑
                      </button>
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
        </div>
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

      {dialog?.kind === "edit" ? (
        <EditDocumentDialog
          target={dialog.target}
          onCancel={() => setDialog(null)}
          onDone={(needsRechunk, updated) => {
            load();
            // 改了分块参数就换成一个需要用户做决定的弹层，而不是会自动消失的 toast
            if (needsRechunk) {
              setDialog({ kind: "rechunk", target: updated });
            } else {
              setDialog(null);
              setToast(SAVED_MESSAGE);
            }
          }}
        />
      ) : null}

      {dialog?.kind === "rechunk" ? (
        <ConfirmDialog
          title="分块参数已保存"
          confirmLabel="立即重新处理"
          cancelLabel="稍后再说"
          action={() => ingestion.trigger(dialog.target.id)}
          onCancel={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            setRefreshKey((n) => n + 1);
            load();
          }}
        >
          <p>
            现有的 <strong>{dialog.target.chunkCount}</strong> 个分块仍然是按旧参数切出来的。
            要立即用新参数重新处理吗？
          </p>
          <p className="muted state-detail">重新处理会为每个分块重新计算向量。</p>
        </ConfirmDialog>
      ) : null}

      {dialog?.kind === "addUrl" ? (
        <AddUrlDialog
          kbId={kbId}
          onCancel={() => setDialog(null)}
          onDone={() => closeDialogAndReload(URL_ADDED_MESSAGE)}
        />
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

/** 分块参数三件套的编辑与校验，编辑文档与添加链接两个表单共用（ui-spec §11 / §12）。 */
function ChunkParamFields({
  strategy,
  chunkSize,
  overlap,
  onChange,
}: {
  strategy: ChunkStrategy;
  chunkSize: string;
  overlap: string;
  onChange: (patch: { strategy?: ChunkStrategy; chunkSize?: string; overlap?: string }) => void;
}) {
  return (
    <>
      <label>
        分块策略
        <select
          value={strategy}
          onChange={(e) => onChange({ strategy: e.target.value as ChunkStrategy })}
        >
          {(Object.keys(STRATEGY_LABEL) as ChunkStrategy[]).map((key) => (
            <option key={key} value={key}>
              {STRATEGY_LABEL[key]}
            </option>
          ))}
        </select>
        {/* 两种策略的差别用户看名字猜不出来，选中项下面跟一句说明 */}
        <span className="field-hint">{STRATEGY_HINT[strategy]}</span>
      </label>
      <div className="field-row">
        <label>
          每块字符数
          <input
            type="number"
            min={1}
            value={chunkSize}
            onChange={(e) => onChange({ chunkSize: e.target.value })}
          />
        </label>
        <label>
          重叠字符数
          <input
            type="number"
            min={0}
            value={overlap}
            onChange={(e) => onChange({ overlap: e.target.value })}
          />
        </label>
      </div>
    </>
  );
}

/**
 * 校验分块参数。第三条不是形式校验：`overlap >= chunkSize` 会让切分步进 ≤ 0，
 * 固定长度策略原地打转停不下来——是**不终止**而不是"效果不好"，所以前后端都挡。
 */
function validateChunkParams(chunkSize: string, overlap: string): string | null {
  const size = Number(chunkSize);
  const over = Number(overlap);
  if (!(size > 0)) {
    return FORM_ERRORS.chunkSizePositive;
  }
  if (!(over >= 0) || over >= size) {
    return FORM_ERRORS.overlapLessThanSize;
  }
  return null;
}

function EditDocumentDialog({
  target,
  onCancel,
  onDone,
}: {
  target: SourceDocument;
  onCancel: () => void;
  onDone: (needsRechunk: boolean, updated: SourceDocument) => void;
}) {
  const isUrl = target.sourceType === "URL";
  const [name, setName] = useState(target.name);
  const [strategy, setStrategy] = useState<ChunkStrategy>(target.chunkStrategy);
  const [chunkSize, setChunkSize] = useState(String(target.chunkConfig.chunkSize));
  const [overlap, setOverlap] = useState(String(target.chunkConfig.overlap));
  const [sourceUri, setSourceUri] = useState(target.sourceUri ?? "");
  const [syncEnabled, setSyncEnabled] = useState(target.syncEnabled);
  const [syncCron, setSyncCron] = useState(target.syncCron ?? "");
  const [error, setError] = useState<string | null>(null);
  const [serverError, setServerError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  const onSubmit = () => {
    if (!name.trim()) {
      return setError(FORM_ERRORS.nameRequired);
    }
    const paramError = validateChunkParams(chunkSize, overlap);
    if (paramError) {
      return setError(paramError);
    }
    if (isUrl) {
      if (!sourceUri.trim()) {
        return setError(FORM_ERRORS.uriRequired);
      }
      if (!/^https?:\/\//i.test(sourceUri.trim())) {
        return setError(FORM_ERRORS.uriProtocol);
      }
      if (syncEnabled && !syncCron.trim()) {
        return setError(FORM_ERRORS.cronRequired);
      }
    }
    setError(null);
    setServerError(null);
    setSaving(true);
    documents
      .update(target.id, {
        name: name.trim(),
        chunkStrategy: strategy,
        chunkSize: Number(chunkSize),
        overlap: Number(overlap),
        ...(isUrl
          ? { sourceUri: sourceUri.trim(), syncEnabled, syncCron: syncCron.trim() || undefined }
          : {}),
      })
      // needsRechunk 由后端算（它才知道 revision 与库中旧参数），前端不重复判断
      .then((updated) => onDone(updated.needsRechunk, updated))
      .catch((cause: unknown) => setServerError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title="编辑文档"
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={saving}>
            取消
          </button>
          <button type="button" className="primary" onClick={onSubmit} disabled={saving}>
            {saving ? "保存中…" : "保存"}
          </button>
        </>
      }
    >
      <label>
        名称
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={256} autoFocus />
      </label>
      <ChunkParamFields
        strategy={strategy}
        chunkSize={chunkSize}
        overlap={overlap}
        onChange={(patch) => {
          if (patch.strategy !== undefined) setStrategy(patch.strategy);
          if (patch.chunkSize !== undefined) setChunkSize(patch.chunkSize);
          if (patch.overlap !== undefined) setOverlap(patch.overlap);
        }}
      />
      {/*
        本地上传的文档**不显示**来源地址与定时同步，而不是灰掉：它们对 FILE 来源根本不存在
        （后端传了会 400），灰着摆在那里只会让用户以为"有个开关我打不开"（ui-spec §11）。
      */}
      {isUrl ? (
        <>
          <label>
            来源地址
            <input value={sourceUri} onChange={(e) => setSourceUri(e.target.value)} />
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={syncEnabled}
              onChange={(e) => setSyncEnabled(e.target.checked)}
            />
            开启定时同步
          </label>
          {syncEnabled ? (
            <label>
              同步规则
              <input
                value={syncCron}
                onChange={(e) => setSyncCron(e.target.value)}
                placeholder="0 0 3 * * ?"
              />
              <span className="field-hint">
                用 cron 表达式，例如 <code>0 0 3 * * ?</code> 表示每天凌晨 3 点。
                <strong>两次同步的间隔不能小于 10 分钟。</strong>
              </span>
            </label>
          ) : null}
        </>
      ) : null}
      {error ? <p className="inline-error">{error}</p> : null}
      {serverError ? <p className="inline-error">{toUserMessage(serverError).title}</p> : null}
    </Modal>
  );
}

function AddUrlDialog({
  kbId,
  onCancel,
  onDone,
}: {
  kbId: number;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [sourceUri, setSourceUri] = useState("");
  const [name, setName] = useState("");
  const [strategy, setStrategy] = useState<ChunkStrategy>("RECURSIVE");
  const [chunkSize, setChunkSize] = useState("1000");
  const [overlap, setOverlap] = useState("100");
  const [syncEnabled, setSyncEnabled] = useState(false);
  const [syncCron, setSyncCron] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [serverError, setServerError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  const onSubmit = () => {
    if (!sourceUri.trim()) {
      return setError(FORM_ERRORS.uriRequired);
    }
    if (!/^https?:\/\//i.test(sourceUri.trim())) {
      return setError(FORM_ERRORS.uriProtocol);
    }
    const paramError = validateChunkParams(chunkSize, overlap);
    if (paramError) {
      return setError(paramError);
    }
    if (syncEnabled && !syncCron.trim()) {
      return setError(FORM_ERRORS.cronRequired);
    }
    setError(null);
    setServerError(null);
    setSaving(true);
    documents
      .addUrl(kbId, {
        sourceUri: sourceUri.trim(),
        name: name.trim() || undefined,
        chunkStrategy: strategy,
        chunkSize: Number(chunkSize),
        overlap: Number(overlap),
        syncEnabled,
        syncCron: syncEnabled ? syncCron.trim() : undefined,
      })
      .then(onDone)
      // 失败时弹层不关：抓取失败的原因几乎总是用户能修正的（地址打错、对方 404、格式不对），
      // 关掉就等于让他重填一遍
      .catch((cause: unknown) => setServerError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title="添加链接"
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={saving}>
            取消
          </button>
          <button
            type="button"
            className="primary"
            onClick={onSubmit}
            disabled={saving || !sourceUri.trim()}
          >
            {/* 抓取是同步发生的，可能要下载几十 MB，不能只转个圈不说话 */}
            {saving ? "抓取中…" : "添加"}
          </button>
        </>
      }
    >
      <label>
        文件直链
        <input
          value={sourceUri}
          onChange={(e) => setSourceUri(e.target.value)}
          placeholder="https://example.com/handbook.md"
          autoFocus
        />
        {/*
          常驻提示，不是等出错才说：服务端无法可靠判别一个 URL 指向文件还是网页
          （Content-Type 会撒谎），贴错只会得到一句 415，那句话本身不解释他错在哪。
        */}
        <span className="field-hint">
          请填写<strong>文件的直接下载地址</strong>（以 .txt / .md / .pdf / .docx
          结尾），不是网页地址。网页地址抓下来的是整个页面的 HTML。
        </span>
      </label>
      <label>
        名称（可选）
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="留空则取地址最后一段"
          maxLength={256}
        />
      </label>
      <ChunkParamFields
        strategy={strategy}
        chunkSize={chunkSize}
        overlap={overlap}
        onChange={(patch) => {
          if (patch.strategy !== undefined) setStrategy(patch.strategy);
          if (patch.chunkSize !== undefined) setChunkSize(patch.chunkSize);
          if (patch.overlap !== undefined) setOverlap(patch.overlap);
        }}
      />
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={syncEnabled}
          onChange={(e) => setSyncEnabled(e.target.checked)}
        />
        开启定时同步
      </label>
      {/* 关闭时隐藏而不是灰掉，同 ui-spec §11 的理由 */}
      {syncEnabled ? (
        <label>
          同步规则
          <input
            value={syncCron}
            onChange={(e) => setSyncCron(e.target.value)}
            placeholder="0 0 3 * * ?"
          />
          <span className="field-hint">
            用 cron 表达式，例如 <code>0 0 3 * * ?</code> 表示每天凌晨 3 点。
            <strong>两次同步的间隔不能小于 10 分钟。</strong>
          </span>
        </label>
      ) : null}
      {error ? <p className="inline-error">{error}</p> : null}
      {serverError ? <p className="inline-error">{toUserMessage(serverError).title}</p> : null}
    </Modal>
  );
}

/**
 * 二次确认弹层。文案由调用方以 children 传入——ui-spec §9 要求每种操作点明**自己的**代价，
 * 抽成一句通用的"确定要继续吗"就把这份规格作废了。
 */
function ConfirmDialog({
  title,
  confirmLabel,
  cancelLabel = "取消",
  danger,
  action,
  onCancel,
  onDone,
  children,
}: {
  title: string;
  confirmLabel: string;
  /** 「稍后再说」这类场景下取消不是"放弃操作"，措辞要跟着变（ui-spec §11）。 */
  cancelLabel?: string;
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
            {cancelLabel}
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
