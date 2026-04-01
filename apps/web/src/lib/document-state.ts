import type {
  DocumentDetail,
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
    return "Unknown";
  }

  return new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function formatRelativeTime(value: string | null | undefined): string {
  if (!value) {
    return "Unknown";
  }

  const diffMs = Date.now() - new Date(value).getTime();
  const diffMinutes = Math.max(0, Math.round(diffMs / 60000));
  if (diffMinutes < 60) {
    return `${diffMinutes}m ago`;
  }

  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours}h ago`;
  }

  const diffDays = Math.round(diffHours / 24);
  return `${diffDays}d ago`;
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
      return "Pending";
    case "processing":
      return "Processing";
    case "success":
      return "Success";
    case "failed":
      return "Failed";
    default:
      return "Unknown";
  }
}

export function jobStatusLabel(status: JobStatusResponse["status"] | string | undefined): string {
  switch (status) {
    case "queued":
      return "Queued";
    case "running":
      return "Running";
    case "succeeded":
      return "Succeeded";
    case "failed":
      return "Failed";
    case "dead":
      return "Dead";
    default:
      return "Unknown";
  }
}

export function sourceTypeLabel(value: string | undefined): string {
  switch (value) {
    case "text":
      return "Text";
    case "file":
      return "File";
    case "link":
      return "Link";
    default:
      return "All source types";
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
      return "Active";
    case "archived":
      return "Archived";
    default:
      return "Unknown";
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
