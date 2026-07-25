import { api } from "./client";

export type DocumentStatus = "PENDING" | "RUNNING" | "SUCCESS" | "FAILED";

export interface SourceDocument {
  id: number;
  kbId: number;
  name: string;
  sourceType: "FILE" | "URL";
  fileSize: number | null;
  contentType: string | null;
  status: DocumentStatus;
  revision: number;
  chunkCount: number;
  errorMessage: string | null;
  enabled: boolean;
  chunkStrategy: "FIXED_SIZE" | "RECURSIVE";
  chunkConfig: { chunkSize: number; overlap: number };
  createTime: string;
}

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
};

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
