import type {
  DocumentDetail,
  DocumentEvidenceItem,
  DocumentUploadStatus,
  DocumentListResponse,
  JobStatusResponse,
  ListDocumentsQuery,
  IndexStatus,
  ParseStatus,
  SourceType,
  TagStatus
} from "@xrag/shared-types";

export interface SearchFilters {
  q: string;
  source_type: string;
  ocr_status: string;
  parse_status: string;
  index_status: string;
  upload_status: string;
  diagnosis_code: string;
  tags: string;
  date_from: string;
  date_to: string;
  page: number;
  page_size: number;
}

export const defaultSearchFilters: SearchFilters = {
  q: "",
  source_type: "",
  ocr_status: "",
  parse_status: "",
  index_status: "",
  upload_status: "",
  diagnosis_code: "",
  tags: "",
  date_from: "",
  date_to: "",
  page: 1,
  page_size: 20
};

export function normalizeSearchFilters(input: Record<string, unknown>): SearchFilters {
  return {
    q: typeof input.q === "string" ? input.q : "",
    source_type: typeof input.source_type === "string" ? input.source_type : "",
    ocr_status: typeof input.ocr_status === "string" ? input.ocr_status : "",
    parse_status: typeof input.parse_status === "string" ? input.parse_status : "",
    index_status: typeof input.index_status === "string" ? input.index_status : "",
    upload_status: typeof input.upload_status === "string" ? input.upload_status : "",
    diagnosis_code: typeof input.diagnosis_code === "string" ? input.diagnosis_code : "",
    tags: typeof input.tags === "string" ? input.tags : "",
    date_from: typeof input.date_from === "string" ? input.date_from : "",
    date_to: typeof input.date_to === "string" ? input.date_to : "",
    page: normalizePositiveNumber(input.page, 1),
    page_size: normalizePositiveNumber(input.page_size, 20)
  };
}

export function serializeSearchFilters(filters: SearchFilters): Record<string, string | number | undefined> {
  return {
    q: filters.q.trim() || undefined,
    source_type: filters.source_type || undefined,
    ocr_status: filters.ocr_status || undefined,
    parse_status: filters.parse_status || undefined,
    index_status: filters.index_status || undefined,
    upload_status: filters.upload_status || undefined,
    diagnosis_code: filters.diagnosis_code || undefined,
    tags: filters.tags || undefined,
    date_from: filters.date_from || undefined,
    date_to: filters.date_to || undefined,
    page: filters.page > 1 ? filters.page : undefined,
    page_size: filters.page_size !== 20 ? filters.page_size : undefined
  };
}

export function buildDocumentsQuery(filters: SearchFilters) {
  return {
    q: filters.q.trim() || undefined,
    source_type: normalizeSourceType(filters.source_type),
    ocr_status: normalizeOcrStatus(filters.ocr_status),
    parse_status: filters.parse_status || undefined,
    index_status: normalizeIndexStatus(filters.index_status),
    upload_status: filters.upload_status || undefined,
    diagnosis_code: filters.diagnosis_code || undefined,
    tags: filters.tags || undefined,
    date_from: filters.date_from || undefined,
    date_to: filters.date_to || undefined,
    page: filters.page,
    page_size: filters.page_size
  } satisfies ListDocumentsQuery;
}

export function splitTags(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

export function joinTags(value: string[]): string {
  return value.join(", ");
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "未知";
  }

  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function formatRelativeTime(value: string | null | undefined): string {
  if (!value) {
    return "未知";
  }

  const diffMs = Date.now() - new Date(value).getTime();
  const diffMinutes = Math.max(0, Math.round(diffMs / 60000));
  if (diffMinutes < 60) {
    return `${diffMinutes} 分钟前`;
  }

  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} 小时前`;
  }

  const diffDays = Math.round(diffHours / 24);
  return `${diffDays} 天前`;
}

export function isParseActive(status: ParseStatus | string | undefined): boolean {
  return status === "pending" || status === "processing";
}

export function isJobActive(status: JobStatusResponse["status"] | string | undefined): boolean {
  return status === "queued" || status === "running";
}

export function parseStatusLabel(status: ParseStatus | string | undefined): string {
  switch (status) {
    case "pending":
      return "待处理";
    case "processing":
      return "处理中";
    case "success":
      return "成功";
    case "failed":
      return "失败";
    default:
      return "未知";
  }
}

export function indexStatusLabel(status: IndexStatus | string | undefined): string {
  switch (status) {
    case "not_indexed":
      return "未索引";
    case "queued":
      return "排队中";
    case "chunking":
      return "切分中";
    case "embedding":
      return "向量化中";
    case "ready":
      return "已可引用";
    case "failed":
      return "索引失败";
    case "stale":
      return "已过期";
    default:
      return "未知";
  }
}

export function jobStatusLabel(status: JobStatusResponse["status"] | string | undefined): string {
  switch (status) {
    case "queued":
      return "排队中";
    case "running":
      return "执行中";
    case "succeeded":
      return "成功";
    case "failed":
      return "失败";
    case "dead":
      return "已终止";
    default:
      return "未知";
  }
}

export function sourceTypeLabel(value: string | undefined): string {
  switch (value) {
    case "text":
      return "文本";
    case "file":
      return "文件";
    case "pdf":
      return "PDF";
    case "link":
      return "链接";
    default:
      return "全部来源";
  }
}

export function parseStatusTone(status: ParseStatus | string | undefined) {
  if (status === "success") {
    return "success" as const;
  }

  if (status === "failed") {
    return "warning" as const;
  }

  return "info" as const;
}

export function indexStatusTone(status: IndexStatus | string | undefined) {
  if (status === "ready") {
    return "success" as const;
  }

  if (status === "failed" || status === "stale") {
    return "warning" as const;
  }

  if (status === "queued" || status === "chunking" || status === "embedding") {
    return "info" as const;
  }

  return "default" as const;
}

export function uploadStatusLabel(status: DocumentUploadStatus | string | null | undefined): string {
  switch (status) {
    case "draft":
      return "草稿";
    case "initiated":
      return "已初始化";
    case "uploading":
      return "上传中";
    case "verifying":
      return "校验中";
    case "uploaded":
      return "已上传";
    case "failed":
      return "上传失败";
    default:
      return "未上传";
  }
}

export function diagnosisLabel(code: string | null | undefined): string {
  switch (code) {
    case "storage_presign_failed":
      return "存储签名失败";
    case "multipart_part_failed":
      return "分片上传失败";
    case "upload_complete_invalid_parts":
      return "上传完成校验失败";
    case "object_missing_on_complete":
      return "对象校验失败";
    case "pdf_parse_runtime_error":
      return "PDF 解析器运行时异常";
    case "pdf_parse_unsupported":
      return "PDF 暂不支持解析";
    case "pdf_parse_timeout":
      return "PDF 解析超时";
    case "pdf_parse_empty_text":
      return "PDF 未提取到文本";
    case "ocr_runtime_error":
      return "OCR 运行时异常";
    case "ocr_timeout":
      return "OCR 超时";
    case "ocr_no_text_detected":
      return "OCR 未识别到有效文本";
    case "link_fetch_timeout":
      return "链接抓取超时";
    case "link_fetch_blocked":
      return "链接抓取被阻止";
    case "link_extract_empty":
      return "链接正文提取为空";
    case "link_invalid_url":
      return "链接地址无效";
    case "search_projection_stale":
      return "搜索投影需要刷新";
    case "index_chunk_failed":
      return "索引切分失败";
    case "index_embedding_failed":
      return "索引向量化失败";
    case "retrieval_no_hits":
      return "检索未命中";
    case "retrieval_scope_empty":
      return "范围内没有可引用文档";
    case "answer_insufficient_evidence":
      return "证据不足";
    case "citation_missing":
      return "缺少引用";
    case "provider_timeout":
      return "模型调用超时";
    case "queue_backlog":
      return "解析任务入队失败";
    default:
      return "无诊断";
  }
}

export function isIndexActive(status: IndexStatus | string | undefined): boolean {
  return status === "queued" || status === "chunking" || status === "embedding";
}

export function isCitationReady(status: IndexStatus | string | undefined, citationReady: boolean | undefined): boolean {
  return status === "ready" && citationReady === true;
}

export function summarizeIndexReadiness(
  status: IndexStatus | string | undefined,
  citationReady: boolean | undefined,
  indexedAt: string | null | undefined
): string {
  if (status === "ready" && citationReady) {
    return indexedAt ? `已可引用 · ${formatRelativeTime(indexedAt)}` : "已可引用";
  }

  if (status === "stale") {
    return "已过期，需要重建索引";
  }

  if (status === "failed") {
    return "索引失败，建议重试";
  }

  if (status === "chunking" || status === "embedding" || status === "queued") {
    return `${indexStatusLabel(status)}，正在准备可引用证据`;
  }

  return "尚未建立可引用索引";
}

export function summarizeSearchScopeSnapshot(
  totalCount: number,
  readyCount: number,
  activeCount: number,
  staleOrFailedCount: number
): string {
  if (readyCount > 0) {
    return `${readyCount}/${totalCount} 条结果已可引用，可作为 search_result 作用域快照候选`;
  }

  if (activeCount > 0) {
    return `${activeCount}/${totalCount} 条结果仍在建立索引，建议等待完成后再整理 search_result 快照`;
  }

  if (staleOrFailedCount > 0) {
    return `${staleOrFailedCount}/${totalCount} 条结果索引失效，快照前建议先重建索引`;
  }

  return `${totalCount} 条结果可作为 search_result 作用域候选`;
}

export function describeEvidenceLocator(item: Pick<DocumentEvidenceItem, "section_label" | "page_ref" | "locator">): string {
  const parts: string[] = [];

  if (item.section_label) {
    parts.push(item.section_label);
  }

  if (item.page_ref) {
    parts.push(item.page_ref);
  }

  const locatorText = formatLocator(item.locator);
  if (locatorText) {
    parts.push(locatorText);
  }

  return parts.length > 0 ? parts.join(" · ") : "无可显示定位";
}

export function jobStatusTone(status: JobStatusResponse["status"] | string | undefined) {
  if (status === "succeeded") {
    return "success" as const;
  }

  if (status === "failed" || status === "dead") {
    return "warning" as const;
  }

  return "info" as const;
}

export function tagStatusLabel(status: TagStatus | string | undefined): string {
  switch (status) {
    case "active":
      return "启用";
    case "archived":
      return "归档";
    default:
      return "未知";
  }
}

function normalizePositiveNumber(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      return parsed;
    }
  }

  return fallback;
}

function normalizeSourceType(value: string): SourceType | undefined {
  if (value === "text" || value === "file" || value === "pdf" || value === "link") {
    return value;
  }

  return undefined;
}

function normalizeOcrStatus(value: string) {
  if (value === "not_required" || value === "queued" || value === "processing" || value === "success" || value === "failed") {
    return value;
  }

  return undefined;
}

function normalizeIndexStatus(value: string): IndexStatus | undefined {
  if (
    value === "not_indexed" ||
    value === "queued" ||
    value === "chunking" ||
    value === "embedding" ||
    value === "ready" ||
    value === "failed" ||
    value === "stale"
  ) {
    return value;
  }

  return undefined;
}

function formatLocator(locator: Record<string, unknown> | null): string {
  if (!locator) {
    return "";
  }

  const entries = Object.entries(locator).filter(([, value]) => value !== null && value !== undefined && value !== "");
  if (entries.length === 0) {
    return "";
  }

  return entries
    .map(([key, value]) => `${key}: ${formatLocatorValue(value)}`)
    .join(" · ");
}

function formatLocatorValue(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }

  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }

  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
