import type { AnswerScope, AnswerScopeMode, AnswerSessionStatus, RetrievalMode } from "@xrag/shared-types";

export const ANSWER_SCOPE_MODE_OPTIONS: Array<{ value: AnswerScopeMode; label: string; description: string }> = [
  { value: "global", label: "全库", description: "在整个知识库里检索" },
  { value: "document", label: "单文档", description: "限制到一个文档" },
  { value: "search_result", label: "搜索结果", description: "限制到一组手动指定的文档" }
];

const TERMINAL_STATUSES: AnswerSessionStatus[] = ["answered", "needs_scope", "refused", "failed"];

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

export function summarizeAnswerScope(scope: AnswerScope): string {
  if (scope.mode === "document") {
    const documentId = typeof scope.payload?.document_id === "string" ? scope.payload.document_id : "未知文档";
    return `单文档 · ${documentId}`;
  }

  if (scope.mode === "search_result") {
    const documentIds = Array.isArray(scope.payload?.document_ids) ? scope.payload.document_ids : [];
    const truncated = scope.payload?.truncated === true ? "，已截断" : "";
    return `搜索结果 · ${documentIds.length} 个文档${truncated}`;
  }

  return "全库";
}

export function summarizeScopePayload(scope: AnswerScope): string {
  if (scope.mode === "global") {
    return "覆盖整个知识库。";
  }

  if (scope.mode === "document") {
    const documentId = typeof scope.payload?.document_id === "string" ? scope.payload.document_id : "未知";
    return `作用域固定到文档 ${documentId}。`;
  }

  const documentIds = Array.isArray(scope.payload?.document_ids) ? scope.payload.document_ids : [];
  const truncated = scope.payload?.truncated === true ? "，入参标记为截断" : "";
  return `作用域限制到 ${documentIds.length} 个文档${truncated}。`;
}

export function parseDocumentIdList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean);
}
