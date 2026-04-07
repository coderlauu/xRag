import type {
  DocumentDetail,
  DocumentUploadStatus,
  DocumentListResponse,
  JobStatusResponse,
  ListDocumentsQuery,
  ParseStatus,
  SourceType,
  TagStatus
} from "@xrag/shared-types";

export interface SearchFilters {
  q: string;
  source_type: string;
  parse_status: string;
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
  parse_status: "",
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
    parse_status: typeof input.parse_status === "string" ? input.parse_status : "",
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
    parse_status: filters.parse_status || undefined,
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
    parse_status: filters.parse_status || undefined,
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
    case "queue_backlog":
      return "解析任务入队失败";
    default:
      return "无诊断";
  }
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
  if (value === "text" || value === "file" || value === "link") {
    return value;
  }

  return undefined;
}
