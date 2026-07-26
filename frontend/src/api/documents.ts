import { api, apiUrl } from "./client";

export type DocumentStatus = "PENDING" | "RUNNING" | "SUCCESS" | "FAILED";

export type ChunkStrategy = "FIXED_SIZE" | "RECURSIVE";

export interface SourceDocument {
  id: number;
  kbId: number;
  name: string;
  sourceType: "FILE" | "URL";
  fileSize: number | null;
  contentType: string | null;
  /** 仅 URL 来源。 */
  sourceUri: string | null;
  status: DocumentStatus;
  revision: number;
  chunkCount: number;
  errorMessage: string | null;
  enabled: boolean;
  chunkStrategy: ChunkStrategy;
  chunkConfig: { chunkSize: number; overlap: number };
  syncEnabled: boolean;
  syncCron: string | null;
  nextSyncTime: string | null;
  /** 上次检查时间。URL 来源打开源文件时用它标明"这是哪一刻的快照"（ui-spec §13）。 */
  lastSyncTime: string | null;
  createTime: string;
}

/** 分块策略的中文表述与一句话说明（ui-spec §11），**不在组件里临时决定**。 */
export const STRATEGY_LABEL: Record<ChunkStrategy, string> = {
  RECURSIVE: "递归分隔符",
  FIXED_SIZE: "固定长度",
};

export const STRATEGY_HINT: Record<ChunkStrategy, string> = {
  RECURSIVE: "优先在段落、句子边界切分，尽量不切断完整意思。",
  FIXED_SIZE: "严格按字符数切，行为完全可预测，但可能从句子中间断开。",
};

/** 状态的中文表述来自 ui-spec.md §2，**不在组件里临时决定**。 */
export const STATUS_LABEL: Record<DocumentStatus, string> = {
  PENDING: "待处理",
  RUNNING: "处理中",
  SUCCESS: "已完成",
  FAILED: "处理失败",
};

export const documents = {
  list: (kbId: number, status?: DocumentStatus, page = 1, size = 20) =>
    api.page<SourceDocument>(`/knowledge-bases/${kbId}/documents`, page, size, { status }),

  uploadFile: (kbId: number, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return api.upload<SourceDocument>(`/knowledge-bases/${kbId}/documents/file`, form);
  },

  addUrl: (kbId: number, body: AddUrlBody) =>
    api.post<SourceDocument>(`/knowledge-bases/${kbId}/documents/url`, body),

  update: (docId: number, body: UpdateDocumentBody) =>
    api.put<SourceDocument & { needsRechunk: boolean }>(`/documents/${docId}`, body),

  setEnabled: (docId: number, enabled: boolean) =>
    api.patch<SourceDocument>(`/documents/${docId}/enabled`, { enabled }),

  remove: (docId: number) => api.delete<void>(`/documents/${docId}`),

  /**
   * 源文件地址（ui-spec §13）。**返回 URL 而不是发请求**——文件可能几十 MB，
   * 用 fetch 拉成 blob 等于先整个读进内存；交给浏览器打开是流式的、还自带下载进度。
   */
  fileUrl: (docId: number) => apiUrl(`/documents/${docId}/file`),
};

export interface AddUrlBody {
  sourceUri: string;
  name?: string;
  chunkStrategy?: ChunkStrategy;
  chunkSize?: number;
  overlap?: number;
  syncEnabled?: boolean;
  syncCron?: string;
}

/** 全字段可选，只提交改动过的（api.md §3）。 */
export interface UpdateDocumentBody {
  name?: string;
  chunkStrategy?: ChunkStrategy;
  chunkSize?: number;
  overlap?: number;
  sourceUri?: string;
  syncEnabled?: boolean;
  syncCron?: string;
}

/** ui-spec §11 / §12 的表单校验文案，与后端逐字一致。 */
export const FORM_ERRORS = {
  nameRequired: "文档名称不能为空。",
  chunkSizePositive: "每块字符数必须大于 0。",
  overlapLessThanSize: "重叠字符数必须小于每块字符数。",
  uriRequired: "来源地址不能为空。",
  uriProtocol: "来源地址必须以 http:// 或 https:// 开头。",
  cronRequired: "开启定时同步时必须填写同步规则。",
} as const;

export const SAVED_MESSAGE = "已保存。";
export const URL_ADDED_MESSAGE =
  "添加成功，文档尚未处理。点击「开始处理」后才会切分并写入向量库。";

/** 文档级启禁用的反馈。禁用要说清代价，因为重新启用会再花一次模型调用的钱。 */
export const DOC_TOGGLED_MESSAGE = {
  true: "文档已启用，向量已重新计算。",
  false: "文档已禁用，其全部向量已清理。",
} as const;
export const DOC_DELETED_MESSAGE = "文档已删除，向量已清理。";

export type RunStatus = "QUEUED" | "RUNNING" | "SUCCESS" | "FAILED" | "SKIPPED";
export type Phase = "DOWNLOAD" | "EXTRACT" | "CHUNK" | "EMBED" | "PERSIST";

export interface IngestionRun {
  id: number;
  docId: number;
  triggerSource: "MANUAL" | "SCHEDULED";
  status: RunStatus;
  phase: Phase | null;
  revision: number | null;
  chunkCount: number | null;
  errorMessage: string | null;
  finishedTime: string | null;
  createTime: string;
}

export interface DocumentDetail extends SourceDocument {
  latestRun: IngestionRun | null;
}

/**
 * 全部中文表述来自 ui-spec.md，**不在组件里临时决定**——同一个状态在两个页面说法不同
 * 是最容易发生也最难在评审中发现的问题。
 */
export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  QUEUED: "排队中",
  RUNNING: "处理中",
  SUCCESS: "成功",
  FAILED: "失败",
  // SKIPPED 不是失败，是"检查过、内容没变、无需处理"。措辞不能让用户以为出了问题。
  SKIPPED: "内容未变化，已跳过",
};

/** 处理中显示"正在做什么"，比一个转圈的图标信息量大得多。 */
export const PHASE_RUNNING_LABEL: Record<Phase, string> = {
  DOWNLOAD: "正在读取文件",
  EXTRACT: "正在提取文本",
  CHUNK: "正在切分内容",
  EMBED: "正在计算向量",
  PERSIST: "正在写入",
};

/** 失败时同一个 phase 要换一种说法，否则"正在计算向量"配上失败标记很怪。 */
export const PHASE_FAILED_LABEL: Record<Phase, string> = {
  DOWNLOAD: "读取文件失败",
  EXTRACT: "文本提取失败",
  CHUNK: "内容切分失败",
  EMBED: "向量计算失败",
  PERSIST: "写入失败",
};

export const TRIGGER_LABEL: Record<DocumentStatus, string> = {
  PENDING: "开始处理",
  SUCCESS: "重新处理",
  FAILED: "重试",
  RUNNING: "处理中",
};

/** 与后端 409 DOCUMENT_PROCESSING 的 message 逐字一致（ui-spec §4）。 */
export const RUNNING_TOOLTIP = "文档正在处理中，请等待处理完成后再操作。";

export const ingestion = {
  trigger: (docId: number) =>
    api.post<{ runId: number }>(`/documents/${docId}/ingestion-runs`, {}),

  detail: (docId: number, signal?: AbortSignal) =>
    api.get<DocumentDetail>(`/documents/${docId}`, { signal }),

  runs: (docId: number) => api.page<IngestionRun>(`/documents/${docId}/ingestion-runs`, 1, 20),
};
