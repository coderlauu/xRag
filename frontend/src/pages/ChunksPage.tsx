import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import {
  chunks as chunksApi,
  CHUNK_ENABLED_HINT,
  CHUNK_ENABLED_LABEL,
  CREATED_MESSAGE,
  DELETED_MESSAGE,
  MAX_BATCH_SIZE,
  PARENT_DISABLED_ADD_TOOLTIP,
  PARENT_DISABLED_TOOLTIP,
  SAVED_DISABLED_MESSAGE,
  SAVED_MESSAGE,
  TOGGLED_MESSAGE,
  UNCHANGED_MESSAGE,
  type BatchToggleResult,
  type DocumentChunk,
} from "../api/chunks";
import { documents, ingestion, RUNNING_TOOLTIP, type DocumentDetail } from "../api/documents";
import { toUserMessage } from "../api/errors";
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";
import { Modal } from "../components/Modal";
import { Toast } from "../components/Toast";

const PAGE_SIZE = 20;

/** 筛选值与后端的 `enabled` 参数：`undefined` 表示不过滤。 */
const FILTERS = [
  { value: "all", label: "全部", enabled: undefined },
  { value: "on", label: "仅已启用", enabled: true },
  { value: "off", label: "仅已禁用", enabled: false },
] as const;

type FilterValue = (typeof FILTERS)[number]["value"];

export function ChunksPage() {
  const { kbId, docId: docIdParam } = useParams();
  const docId = Number(docIdParam);

  const [doc, setDoc] = useState<DocumentDetail | null>(null);
  const [items, setItems] = useState<DocumentChunk[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState<FilterValue>("all");
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [editing, setEditing] = useState<DocumentChunk | null>(null);
  const [creating, setCreating] = useState(false);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [batch, setBatch] = useState<boolean | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [toast, setToast] = useState<string | null>(null);

  const enabledFilter = FILTERS.find((f) => f.value === filter)?.enabled;

  const load = useCallback(() => {
    setError(null);
    Promise.all([ingestion.detail(docId), chunksApi.list(docId, enabledFilter, page, PAGE_SIZE)])
      .then(([detail, result]) => {
        setDoc(detail);
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        setItems(null);
        setError(cause);
      });
  }, [docId, enabledFilter, page]);

  useEffect(load, [load]);

  const dismissToast = useCallback(() => setToast(null), []);

  const isRunning = doc?.status === "RUNNING";
  const parentEnabled = doc?.enabled ?? true;
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const onSaved = (message: string) => {
    setEditing(null);
    setToast(message);
    load();
  };

  // ui-spec §9：单条删除不做二次确认——影响面小、可手工重建，弹层的打扰成本高于收益。
  const onDelete = (chunk: DocumentChunk) => {
    setActionError(null);
    chunksApi
      .remove(chunk.id)
      .then(() => {
        setToast(DELETED_MESSAGE);
        load();
      })
      .catch((cause: unknown) => setActionError(cause));
  };

  const onToggle = (chunk: DocumentChunk) => {
    const next = !chunk.enabled;
    setActionError(null);
    chunksApi
      .setEnabled(chunk.id, next)
      .then(() => {
        setToast(TOGGLED_MESSAGE[String(next) as "true" | "false"]);
        load();
      })
      .catch((cause: unknown) => setActionError(cause));
  };

  const onCreated = () => {
    setCreating(false);
    setToast(CREATED_MESSAGE);
    load();
  };

  const onBatchDone = (result: BatchToggleResult) => {
    setBatch(null);
    setPicked(new Set());
    // toast 用后端返回的**实际**数字，确认框用前端本地估算。两个数字通常一致；不一致就说明
    // 在用户确认的这几秒里数据被别处改动了，此时用户看到的是真实结果而不是自己以为的结果。
    setToast(
      `已处理 ${result.requested} 个分块，其中 ${result.changed} 个状态已变更，${result.alreadyInTargetState} 个原本就是该状态。`,
    );
    load();
  };

  const togglePick = (id: number) =>
    setPicked((current) => {
      const next = new Set(current);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });

  const onTrigger = () => {
    setActionError(null);
    ingestion.trigger(docId).then(load).catch((cause: unknown) => setActionError(cause));
  };

  const toggleExpand = (id: number) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });

  return (
    <section>
      <nav className="breadcrumb">
        <Link to="/knowledge-bases">知识库</Link>
        <span>/</span>
        <Link to={`/knowledge-bases/${kbId}`}>文档</Link>
        <span>/</span>
        <span>{doc?.name ?? "…"}</span>
      </nav>

      {/*
        ui-spec §13：这里才是「查看源文件」的主战场——§6 的手动干预要求用户判断"原文到底
        怎么写的"，看到一段被切坏的内容却没有原文可对照时，他只能凭记忆改。
        新标签打开，不在当前页跳走：用户正在编辑分块，带走他会丢掉滚动位置和展开状态。
      */}
      {doc ? (
        <p className="source-links">
          <a href={documents.fileUrl(doc.id)} target="_blank" rel="noreferrer">
            查看源文件
          </a>
          {doc.sourceType === "URL" ? (
            <>
              {/* URL 来源看到的是**上次抓取到、真正被切分过的那一份**，不说清楚会让用户
                  困惑"原文明明是这样，分块怎么是那样" */}
              <span className="muted">
                （{doc.lastSyncTime ? `${formatTime(doc.lastSyncTime)} 抓取的快照` : "抓取时的快照"}）
              </span>
              {doc.sourceUri ? (
                <>
                  <span className="muted"> · </span>
                  <a href={doc.sourceUri} target="_blank" rel="noreferrer">
                    打开原始链接 ↗
                  </a>
                </>
              ) : null}
            </>
          ) : null}
        </p>
      ) : null}

      <header className="page-header">
        <h1>分块{doc ? `（${doc.chunkCount}）` : ""}</h1>
        {doc ? (
          <button
            type="button"
            className="primary"
            disabled={isRunning || !doc.enabled}
            title={
              isRunning ? RUNNING_TOOLTIP : !doc.enabled ? PARENT_DISABLED_ADD_TOOLTIP : undefined
            }
            onClick={() => setCreating(true)}
          >
            新增分块
          </button>
        ) : null}
      </header>

      {/*
        ui-spec §3：RUNNING 时整页给一条横幅，而不是让用户逐个发现按钮都灰了。
        措辞点明"即将被替换"——重新处理会逻辑删除旧分块再写新的，此刻看到的内容马上就
        不存在了，只说"请稍候"会让人以为编辑还有意义。
      */}
      {isRunning ? (
        <p className="banner">
          <strong>文档正在重新处理，当前分块即将被替换。</strong>处理完成后请刷新。
        </p>
      ) : null}

      {/*
        ui-spec §6：父文档禁用时整页给横幅。注意这里的**不对称**——横幅期间"禁用分块"
        仍然可用，只有"启用分块"和"新增分块"被禁掉。把内容从检索里拿掉任何时候都安全，
        放进去才要求父文档也认。图省事把两个方向一起灰掉是这一段最容易写错的地方。
      */}
      {doc && !doc.enabled ? (
        <p className="banner banner-stop">
          文档已禁用，其中所有分块都不参与检索。<strong>请先启用文档</strong>，再单独调整分块。
        </p>
      ) : null}

      {actionError ? <ErrorState error={actionError} /> : null}
      {error ? <ErrorState error={error} onRetry={load} /> : null}
      {!error && items === null ? <Loading /> : null}

      {items !== null && !error ? (
        <div className="chunk-toolbar">
          {items.length > 0 ? (
            <label className="pick-all">
              <input
                type="checkbox"
                disabled={isRunning}
                checked={picked.size > 0 && picked.size === items.length}
                onChange={(event) =>
                  setPicked(event.target.checked ? new Set(items.map((c) => c.id)) : new Set())
                }
              />
              {picked.size > 0 ? `已选 ${picked.size} 项` : "全选"}
            </label>
          ) : null}

          {/*
            批量启用受父文档状态限制、批量禁用不受——与单条按钮同一条不对称规则
            （ui-spec §6）。图省事把两个按钮一起灰掉是这里最容易写错的地方。
          */}
          <button
            type="button"
            disabled={picked.size === 0 || picked.size > MAX_BATCH_SIZE || isRunning || !parentEnabled}
            title={!parentEnabled ? PARENT_DISABLED_TOOLTIP : isRunning ? RUNNING_TOOLTIP : undefined}
            onClick={() => setBatch(true)}
          >
            批量启用
          </button>
          <button
            type="button"
            disabled={picked.size === 0 || picked.size > MAX_BATCH_SIZE || isRunning}
            title={isRunning ? RUNNING_TOOLTIP : undefined}
            onClick={() => setBatch(false)}
          >
            批量禁用
          </button>
          {/* 上限在界面上先说清楚，而不是等后端报 400（ui-spec §6） */}
          {picked.size > MAX_BATCH_SIZE ? (
            <span className="inline-error">
              单次最多操作 {MAX_BATCH_SIZE} 个分块，请分批处理。
            </span>
          ) : null}

          <label className="filter-label">
            筛选
            <select
              value={filter}
              onChange={(event) => {
                setFilter(event.target.value as FilterValue);
                setPage(1);
                setPicked(new Set());
              }}
            >
              {FILTERS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <span className="muted">共 {total} 个</span>
        </div>
      ) : null}

      {/* 筛选后为空不是空态（ui-spec §8），做成空态会让用户以为数据没了 */}
      {items?.length === 0 && filter !== "all" ? (
        <p className="muted">
          当前筛选条件下没有内容。{" "}
          <button type="button" className="link" onClick={() => setFilter("all")}>
            清除筛选
          </button>
        </p>
      ) : null}

      {/*
        两种"分块为空"必须分开说（ui-spec §8）：revision = 0 是"还没处理"，需要用户动作；
        revision > 0 是"处理过但切不出内容"，多半是空文件或扫描版 PDF。合并成一句话，
        第二种情况的用户会一直点"开始处理"而永远得不到分块。
      */}
      {items?.length === 0 && filter === "all" && doc?.revision === 0 ? (
        <div className="empty-state">
          <p className="state-title">文档还没有处理。</p>
          <p className="state-detail">处理后会切分成分块并写入向量库，届时可在此查看和编辑。</p>
          <button type="button" className="primary" disabled={isRunning} onClick={onTrigger}>
            开始处理
          </button>
        </div>
      ) : null}

      {items?.length === 0 && filter === "all" && doc !== null && doc.revision > 0 ? (
        <div className="empty-state">
          <p className="state-title">这份文档没有切出任何分块。</p>
          <p className="state-detail">可能是文件内容为空或无法提取出文本。</p>
          <button type="button" disabled={isRunning} onClick={onTrigger}>
            重新处理
          </button>
        </div>
      ) : null}

      {items && items.length > 0 ? (
        <ul className="chunk-list">
          {items.map((chunk) => {
            const open = expanded.has(chunk.id);
            const enabledKey = String(chunk.enabled) as "true" | "false";
            return (
              <li key={chunk.id} className={picked.has(chunk.id) ? "chunk picked" : "chunk"}>
                <input
                  type="checkbox"
                  className="chunk-pick"
                  disabled={isRunning}
                  checked={picked.has(chunk.id)}
                  onChange={() => togglePick(chunk.id)}
                  aria-label={`选择分块 ${chunk.chunkIndex}`}
                />
                <div className="chunk-body">
                  <div className="chunk-meta">
                    {/* 序号是分块在原文中的位置，不是行号——删掉中间某块后出现空缺是正常的 */}
                    <span className="chunk-idx">#{chunk.chunkIndex}</span>
                    {/* "约"字是有意的：token 是启发式估算，不能让用户拿它去核对账单 */}
                    <span className="chunk-dims">
                      {chunk.charCount} 字符 · 约 {chunk.tokenCount} token
                    </span>
                    <span
                      className={chunk.enabled ? "tag tag-on" : "tag tag-off"}
                      title={CHUNK_ENABLED_HINT[enabledKey]}
                    >
                      {CHUNK_ENABLED_LABEL[enabledKey]}
                    </span>
                  </div>
                  <p className={open ? "chunk-text" : "chunk-text clip"}>{chunk.content}</p>
                  <button type="button" className="link" onClick={() => toggleExpand(chunk.id)}>
                    {open ? "收起" : "展开全文"}
                  </button>
                </div>
                <div className="chunk-ops">
                  <button
                    type="button"
                    disabled={isRunning}
                    title={isRunning ? RUNNING_TOOLTIP : undefined}
                    onClick={() => setEditing(chunk)}
                  >
                    编辑
                  </button>
                  {/* 启用受父文档状态限制，禁用不受——两个方向的 disabled 条件必须分开写 */}
                  <button
                    type="button"
                    disabled={isRunning || (!chunk.enabled && !parentEnabled)}
                    title={
                      isRunning
                        ? RUNNING_TOOLTIP
                        : !chunk.enabled && !parentEnabled
                          ? PARENT_DISABLED_TOOLTIP
                          : undefined
                    }
                    onClick={() => onToggle(chunk)}
                  >
                    {chunk.enabled ? "禁用" : "启用"}
                  </button>
                  <button
                    type="button"
                    className="danger"
                    disabled={isRunning}
                    title={isRunning ? RUNNING_TOOLTIP : undefined}
                    onClick={() => onDelete(chunk)}
                  >
                    删除
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}

      {total > PAGE_SIZE ? (
        <div className="pager">
          <button type="button" disabled={page <= 1} onClick={() => setPage((n) => n - 1)}>
            上一页
          </button>
          <span className="muted">
            第 {page} / {lastPage} 页
          </span>
          <button type="button" disabled={page >= lastPage} onClick={() => setPage((n) => n + 1)}>
            下一页
          </button>
        </div>
      ) : null}

      {editing ? (
        <EditChunkDialog target={editing} onCancel={() => setEditing(null)} onDone={onSaved} />
      ) : null}

      {creating ? (
        <CreateChunkDialog docId={docId} onCancel={() => setCreating(false)} onDone={onCreated} />
      ) : null}

      {batch !== null && items ? (
        <BatchToggleDialog
          docId={docId}
          target={batch}
          selected={items.filter((chunk) => picked.has(chunk.id))}
          onCancel={() => setBatch(null)}
          onDone={onBatchDone}
        />
      ) : null}

      {toast ? <Toast message={toast} onDismiss={dismissToast} /> : null}
    </section>
  );
}

/**
 * 后端返回的是 ISO 时间串。这里只要"哪一刻抓的"，精确到分钟足够，秒和时区反而是噪声。
 * 解析失败时原样返回——宁可显示一个丑一点的字符串，也不要让整页崩掉。
 */
function formatTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) {
    return iso;
  }
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

function BatchToggleDialog({
  docId,
  target,
  selected,
  onCancel,
  onDone,
}: {
  docId: number;
  target: boolean;
  selected: DocumentChunk[];
  onCancel: () => void;
  onDone: (result: BatchToggleResult) => void;
}) {
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  // 确认框的数字由**已加载的列表本地算出**，不额外请求后端（ui-spec §6）
  const willChange = selected.filter((chunk) => chunk.enabled !== target).length;
  const unchanged = selected.length - willChange;

  const onConfirm = () => {
    setSaving(true);
    setError(null);
    chunksApi
      .setEnabledBatch(docId, selected.map((chunk) => chunk.id), target)
      .then(onDone)
      .catch((cause: unknown) => setError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title={target ? "批量启用分块" : "批量禁用分块"}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={saving}>
            取消
          </button>
          <button type="button" className="primary" onClick={onConfirm} disabled={saving}>
            {saving ? "处理中…" : target ? "确认启用" : "确认禁用"}
          </button>
        </>
      }
    >
      <p>
        已选中 <strong>{selected.length}</strong> 个分块，其中 <strong>{willChange}</strong>{" "}
        个将从「{target ? "已禁用" : "已启用"}」变为「{target ? "已启用" : "已禁用"}」，
        <strong>{unchanged}</strong> 个已是目标状态、不会变化。
      </p>
      <p className="muted state-detail">
        {target
          ? "启用后会为这些分块重新计算向量，会产生一次模型调用费用。"
          : "禁用后这些分块的向量会被清理，不再参与检索。"}
      </p>
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}

function CreateChunkDialog({
  docId,
  onCancel,
  onDone,
}: {
  docId: number;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [content, setContent] = useState("");
  const [index, setIndex] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  const onSubmit = () => {
    setSaving(true);
    setError(null);
    chunksApi
      .create(docId, content, index.trim() === "" ? undefined : Number(index))
      .then(onDone)
      .catch((cause: unknown) => setError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title="新增分块"
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
            disabled={saving || !content.trim()}
          >
            {saving ? "新增中…" : "新增"}
          </button>
        </>
      }
    >
      <label>
        内容
        <textarea
          value={content}
          onChange={(event) => setContent(event.target.value)}
          rows={10}
          placeholder="输入分块内容……"
          autoFocus
        />
      </label>
      <label>
        插入位置（可选）
        <input
          type="number"
          min={0}
          value={index}
          onChange={(event) => setIndex(event.target.value)}
          placeholder="留空则追加到末尾"
        />
      </label>
      {/*
        序号允许重复，且指定序号**不会重排**其他分块（ui-spec §6 / data-model §3.3）。
        这两件事必须写在表单上：用户填 1 时会以为原来的 #1 被顶到 #2 去了，实际是两条
        #1 相邻展示。不说清楚，看到结果只会以为功能坏了。
      */}
      <p className="muted state-detail">
        留空则追加到末尾（当前最大序号 + 1，已删除分块的序号也算在内）。填写已存在的序号不会重排其他分块，两条同号分块会相邻展示。新增后立即计算向量并参与检索。
      </p>
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}

function EditChunkDialog({
  target,
  onCancel,
  onDone,
}: {
  target: DocumentChunk;
  onCancel: () => void;
  onDone: (message: string) => void;
}) {
  const [content, setContent] = useState(target.content);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  // 内容有没有变，前端自己就知道，不需要后端多回一个字段：判据与后端完全一致
  // （逐字符比较原内容，两边都不做 trim）。禁用的分块不会写向量，措辞必须跟着变。
  const resultMessage = () => {
    if (content === target.content) {
      return UNCHANGED_MESSAGE;
    }
    return target.enabled ? SAVED_MESSAGE : SAVED_DISABLED_MESSAGE;
  };

  const onSubmit = () => {
    setSaving(true);
    setError(null);
    chunksApi
      .updateContent(target.id, content)
      .then(() => onDone(resultMessage()))
      .catch((cause: unknown) => setError(cause))
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      title={`编辑分块 #${target.chunkIndex}`}
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
            disabled={saving || !content.trim()}
          >
            {saving ? "保存中…" : "保存"}
          </button>
        </>
      }
    >
      <label>
        内容
        <textarea
          value={content}
          onChange={(event) => setContent(event.target.value)}
          rows={12}
          autoFocus
        />
      </label>
      <p className="muted state-detail">
        {target.enabled
          ? "保存后该分块的向量会重新计算。内容未变化时不会重算。"
          : "该分块已禁用、不参与检索，保存不会计算向量。"}
      </p>
      {/* 409/502 这类错误提交后才知道，提示必须留在弹层里——关掉弹层再提示，用户改的内容就没了 */}
      {error ? <p className="inline-error">{toUserMessage(error).title}</p> : null}
    </Modal>
  );
}
