import type {
  AnswerClaimFreshnessBadge,
  AnswerScope,
  AnswerScopeMode,
  AnswerSessionStatus,
  RetrievalExclusionReason,
  RetrievalMode,
  ScopeFilterSet,
  SourceType
} from "@xrag/shared-types";

export interface AnswerScopeDraft {
  mode: AnswerScopeMode;
  documentId: string;
  documentIdsText: string;
  searchQuery: string;
  filterTagsText: string;
  filterSourceTypesText: string;
  filterDateFrom: string;
  filterDateTo: string;
}

export interface AskWorkspaceSearchState {
  hasPrefill: boolean;
  scopeDraft: AnswerScopeDraft;
  continuedFromSessionId: string | null;
}

export interface ParsedSourceTypeList {
  values: SourceType[];
  invalid: string[];
}

export const ANSWER_SCOPE_MODE_OPTIONS: Array<{ value: AnswerScopeMode; label: string; description: string }> = [
  { value: "global", label: "全库", description: "在整个知识库里检索" },
  { value: "document", label: "单文档", description: "限制到一个文档" },
  { value: "search_result", label: "搜索结果", description: "限制到一组手动指定的文档" }
];

export const DEFAULT_ANSWER_SCOPE_DRAFT: AnswerScopeDraft = {
  mode: "global",
  documentId: "",
  documentIdsText: "",
  searchQuery: "",
  filterTagsText: "",
  filterSourceTypesText: "",
  filterDateFrom: "",
  filterDateTo: ""
};

const VALID_SOURCE_TYPES: SourceType[] = ["text", "file", "pdf", "link"];
const TERMINAL_STATUSES: AnswerSessionStatus[] = ["answered", "needs_scope", "refused", "failed"];

export function createDefaultAnswerScopeDraft(): AnswerScopeDraft {
  return { ...DEFAULT_ANSWER_SCOPE_DRAFT };
}

export function isAnswerSessionActive(status: AnswerSessionStatus | string | undefined): boolean {
  return status === "idle" || status === "retrieving" || status === "synthesizing";
}

export function isAnswerSessionTerminal(status: AnswerSessionStatus | string | undefined): boolean {
  return Boolean(status && TERMINAL_STATUSES.includes(status as AnswerSessionStatus));
}

export function answerSessionStatusLabel(status: AnswerSessionStatus | string | undefined): string {
  switch (status) {
    case "idle":
      return "待启动";
    case "retrieving":
      return "检索中";
    case "synthesizing":
      return "生成中";
    case "answered":
      return "已回答";
    case "needs_scope":
      return "需要收窄作用域";
    case "refused":
      return "已拒答";
    case "failed":
      return "失败";
    default:
      return "未知";
  }
}

export function answerSessionStatusTone(status: AnswerSessionStatus | string | undefined) {
  if (status === "answered") {
    return "success" as const;
  }

  if (status === "retrieving" || status === "synthesizing" || status === "idle") {
    return "info" as const;
  }

  return "warning" as const;
}

export function answerScopeModeLabel(mode: AnswerScopeMode | string | undefined): string {
  switch (mode) {
    case "global":
      return "全库";
    case "document":
      return "单文档";
    case "search_result":
      return "搜索结果";
    default:
      return "未知";
  }
}

export function retrievalModeLabel(mode: RetrievalMode | string | undefined): string {
  switch (mode) {
    case "hybrid":
      return "混合检索";
    default:
      return "未知";
  }
}

export function answerClaimFreshnessLabel(value: AnswerClaimFreshnessBadge | string | undefined): string {
  switch (value) {
    case "ready":
      return "fresh";
    case "stale_risk":
      return "stale-risk";
    case "unknown":
      return "unknown";
    default:
      return "unknown";
  }
}

export function answerClaimFreshnessTone(value: AnswerClaimFreshnessBadge | string | undefined) {
  switch (value) {
    case "ready":
      return "success" as const;
    case "stale_risk":
      return "warning" as const;
    default:
      return "info" as const;
  }
}

export function retrievalExclusionReasonLabel(value: RetrievalExclusionReason | string | null | undefined): string {
  switch (value) {
    case "deduplicated":
      return "重复候选已折叠";
    case "rerank_cutoff":
      return "超过 rerank 截断阈值";
    case "answer_budget":
      return "超过答案预算";
    case "low_support":
      return "证据支持不足";
    case "citation_unready":
      return "文档索引尚未可引用";
    default:
      return "未说明";
  }
}

export function formatLatencyMs(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "未知";
  }

  return `${Math.round(value)} ms`;
}

export function formatUsd(value: string | null | undefined): string {
  if (!value) {
    return "未知";
  }

  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return value;
  }

  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 4,
    maximumFractionDigits: 4
  }).format(amount);
}

export function formatAnswerUpdatedAt(value: string | null | undefined): string {
  if (!value) {
    return "未知";
  }

  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function summarizeAnswerScope(scope: AnswerScope): string {
  if (scope.mode === "document") {
    const documentId = typeof scope.payload?.document_id === "string" ? scope.payload.document_id : "未知文档";
    return `单文档 · ${documentId}`;
  }

  if (scope.mode === "search_result") {
    const documentIds = Array.isArray(scope.payload?.document_ids) ? scope.payload.document_ids : [];
    const detailParts = [
      scope.payload?.query ? "带查询快照" : null,
      hasScopeFilters(scope.payload?.filters) ? "带过滤条件" : null,
      scope.payload?.truncated === true ? "已截断" : null
    ].filter(Boolean);

    return `搜索结果 · ${documentIds.length} 个文档${detailParts.length > 0 ? ` · ${detailParts.join(" / ")}` : ""}`;
  }

  if (hasScopeFilters(scope.payload?.filters)) {
    return "全库 · 已加过滤条件";
  }

  return "全库";
}

export function summarizeScopePayload(scope: AnswerScope): string {
  if (scope.mode === "global") {
    const filtersSummary = summarizeScopeFilters(scope.payload?.filters);
    return filtersSummary ? `覆盖整个知识库，并限制为 ${filtersSummary}。` : "覆盖整个知识库。";
  }

  if (scope.mode === "document") {
    const documentId = typeof scope.payload?.document_id === "string" ? scope.payload.document_id : "未知";
    return `作用域固定到文档 ${documentId}。`;
  }

  const documentIds = Array.isArray(scope.payload?.document_ids) ? scope.payload.document_ids : [];
  const parts = [`作用域限制到 ${documentIds.length} 个文档`];

  if (scope.payload?.query) {
    parts.push(`查询快照为 “${scope.payload.query}”`);
  }

  const filtersSummary = summarizeScopeFilters(scope.payload?.filters);
  if (filtersSummary) {
    parts.push(`过滤条件为 ${filtersSummary}`);
  }

  if (scope.payload?.truncated === true) {
    parts.push("入参标记为截断");
  }

  return `${parts.join("，")}。`;
}

export function scopeDraftFromAnswerScope(scope: AnswerScope): AnswerScopeDraft {
  const draft = createDefaultAnswerScopeDraft();
  const filters = scope.mode === "document" ? null : scope.payload?.filters;

  draft.mode = scope.mode;
  draft.filterTagsText = (filters?.tags || []).join(", ");
  draft.filterSourceTypesText = (filters?.source_types || []).join(", ");
  draft.filterDateFrom = filters?.date_from || "";
  draft.filterDateTo = filters?.date_to || "";

  if (scope.mode === "document") {
    draft.documentId = scope.payload.document_id;
    return draft;
  }

  if (scope.mode === "search_result") {
    draft.documentIdsText = (scope.payload.document_ids || []).join(", ");
    draft.searchQuery = scope.payload.query || "";
  }

  return draft;
}

export function parseAskWorkspaceSearch(input: Record<string, unknown>): AskWorkspaceSearchState {
  const hasPrefill = [
    "mode",
    "document_id",
    "document_ids",
    "scope_query",
    "filter_tags",
    "filter_source_types",
    "filter_date_from",
    "filter_date_to",
    "continued_from_session_id"
  ].some((key) => typeof input[key] === "string" && input[key].trim() !== "");
  const draft = createDefaultAnswerScopeDraft();
  const mode = normalizeScopeMode(input.mode);

  draft.mode = mode;
  draft.documentId = readString(input.document_id);
  draft.documentIdsText = readString(input.document_ids);
  draft.searchQuery = readString(input.scope_query);
  draft.filterTagsText = readString(input.filter_tags);
  draft.filterSourceTypesText = readString(input.filter_source_types);
  draft.filterDateFrom = readString(input.filter_date_from);
  draft.filterDateTo = readString(input.filter_date_to);

  return {
    hasPrefill,
    scopeDraft: draft,
    continuedFromSessionId: readString(input.continued_from_session_id) || null
  };
}

export function serializeAskWorkspaceSearch(input: {
  scopeDraft: AnswerScopeDraft;
  continuedFromSessionId?: string | null;
}) {
  return {
    mode: input.scopeDraft.mode,
    document_id: input.scopeDraft.mode === "document" ? input.scopeDraft.documentId.trim() || undefined : undefined,
    document_ids:
      input.scopeDraft.mode === "search_result" ? normalizeDelimitedList(input.scopeDraft.documentIdsText).join(",") || undefined : undefined,
    scope_query: input.scopeDraft.mode === "search_result" ? input.scopeDraft.searchQuery.trim() || undefined : undefined,
    filter_tags: input.scopeDraft.filterTagsText.trim() || undefined,
    filter_source_types: input.scopeDraft.filterSourceTypesText.trim() || undefined,
    filter_date_from: input.scopeDraft.filterDateFrom || undefined,
    filter_date_to: input.scopeDraft.filterDateTo || undefined,
    continued_from_session_id: input.continuedFromSessionId?.trim() || undefined
  } satisfies Record<string, string | undefined>;
}

export function parseDocumentIdList(value: string): string[] {
  return normalizeDelimitedList(value);
}

export function parseDelimitedValueList(value: string): string[] {
  return normalizeDelimitedList(value);
}

export function parseSourceTypeList(value: string): ParsedSourceTypeList {
  const values: SourceType[] = [];
  const invalid: string[] = [];

  for (const item of normalizeDelimitedList(value)) {
    if (VALID_SOURCE_TYPES.includes(item as SourceType)) {
      values.push(item as SourceType);
      continue;
    }

    invalid.push(item);
  }

  return {
    values,
    invalid
  };
}

export function buildScopeFiltersFromDraft(draft: AnswerScopeDraft): ScopeFilterSet | null {
  const tags = parseDelimitedValueList(draft.filterTagsText);
  const sourceTypes = parseSourceTypeList(draft.filterSourceTypesText).values;
  const dateFrom = draft.filterDateFrom.trim();
  const dateTo = draft.filterDateTo.trim();

  if (tags.length === 0 && sourceTypes.length === 0 && !dateFrom && !dateTo) {
    return null;
  }

  return {
    tags: tags.length > 0 ? tags : undefined,
    source_types: sourceTypes.length > 0 ? sourceTypes : undefined,
    date_from: dateFrom || undefined,
    date_to: dateTo || undefined
  };
}

export function hasScopeFilters(filters: ScopeFilterSet | null | undefined): boolean {
  return Boolean(
    (filters?.tags && filters.tags.length > 0) ||
      (filters?.source_types && filters.source_types.length > 0) ||
      filters?.date_from ||
      filters?.date_to
  );
}

export function summarizeScopeFilters(filters: ScopeFilterSet | null | undefined): string {
  if (!hasScopeFilters(filters)) {
    return "";
  }

  const parts: string[] = [];

  if (filters?.tags && filters.tags.length > 0) {
    parts.push(`标签 ${filters.tags.join(" / ")}`);
  }

  if (filters?.source_types && filters.source_types.length > 0) {
    parts.push(`来源 ${filters.source_types.join(" / ")}`);
  }

  if (filters?.date_from || filters?.date_to) {
    parts.push(`时间 ${filters?.date_from || "起始"} ~ ${filters?.date_to || "当前"}`);
  }

  return parts.join("，");
}

function normalizeDelimitedList(value: string): string[] {
  return Array.from(
    new Set(
      value
        .split(/[\n,]/)
        .map((item) => item.trim())
        .filter(Boolean)
    )
  );
}

function normalizeScopeMode(value: unknown): AnswerScopeMode {
  return value === "document" || value === "search_result" ? value : "global";
}

function readString(value: unknown): string {
  return typeof value === "string" ? value : "";
}
