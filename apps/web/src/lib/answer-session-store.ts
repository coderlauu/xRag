import type { AnswerScopeDraft } from "./answer-state";
import { createDefaultAnswerScopeDraft } from "./answer-state";

const WORKSPACE_STORAGE_KEY = "xrag:answer-workspace";
const LEGACY_SESSION_STORAGE_KEY = "xrag:last-answer-session-id";

export interface AnswerWorkspaceState {
  activeSessionId: string | null;
  continuedFromSessionId: string | null;
  scopeDraft: AnswerScopeDraft;
}

export function createDefaultAnswerWorkspaceState(): AnswerWorkspaceState {
  return {
    activeSessionId: null,
    continuedFromSessionId: null,
    scopeDraft: createDefaultAnswerScopeDraft()
  };
}

export function loadAnswerWorkspaceState(): AnswerWorkspaceState | null {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    const raw = window.sessionStorage.getItem(WORKSPACE_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<AnswerWorkspaceState>;
      return normalizeWorkspaceState(parsed);
    }

    const legacySessionId = window.sessionStorage.getItem(LEGACY_SESSION_STORAGE_KEY);
    if (!legacySessionId) {
      return null;
    }

    return {
      ...createDefaultAnswerWorkspaceState(),
      activeSessionId: legacySessionId
    };
  } catch {
    return null;
  }
}

export function rememberAnswerWorkspaceState(state: AnswerWorkspaceState) {
  if (typeof window === "undefined") {
    return;
  }

  try {
    const normalized = normalizeWorkspaceState(state);
    window.sessionStorage.setItem(WORKSPACE_STORAGE_KEY, JSON.stringify(normalized));

    if (normalized.activeSessionId) {
      window.sessionStorage.setItem(LEGACY_SESSION_STORAGE_KEY, normalized.activeSessionId);
    } else {
      window.sessionStorage.removeItem(LEGACY_SESSION_STORAGE_KEY);
    }
  } catch {
    // Ignore storage failures in constrained browser contexts.
  }
}

export function clearAnswerWorkspaceState() {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.sessionStorage.removeItem(WORKSPACE_STORAGE_KEY);
    window.sessionStorage.removeItem(LEGACY_SESSION_STORAGE_KEY);
  } catch {
    // Ignore storage failures in constrained browser contexts.
  }
}

export function loadAnswerSessionId(): string | null {
  return loadAnswerWorkspaceState()?.activeSessionId ?? null;
}

export function rememberAnswerSessionId(sessionId: string) {
  const current = loadAnswerWorkspaceState() ?? createDefaultAnswerWorkspaceState();
  rememberAnswerWorkspaceState({
    ...current,
    activeSessionId: sessionId
  });
}

export function clearAnswerSessionId() {
  const current = loadAnswerWorkspaceState();
  if (!current) {
    clearAnswerWorkspaceState();
    return;
  }

  rememberAnswerWorkspaceState({
    ...current,
    activeSessionId: null
  });
}

function normalizeWorkspaceState(value: Partial<AnswerWorkspaceState> | null | undefined): AnswerWorkspaceState {
  const fallback = createDefaultAnswerWorkspaceState();

  return {
    activeSessionId: typeof value?.activeSessionId === "string" && value.activeSessionId.trim() ? value.activeSessionId : null,
    continuedFromSessionId:
      typeof value?.continuedFromSessionId === "string" && value.continuedFromSessionId.trim()
        ? value.continuedFromSessionId
        : null,
    scopeDraft: normalizeScopeDraft(value?.scopeDraft)
  };
}

function normalizeScopeDraft(value: Partial<AnswerScopeDraft> | null | undefined): AnswerScopeDraft {
  return {
    mode: value?.mode === "document" || value?.mode === "search_result" ? value.mode : "global",
    documentId: typeof value?.documentId === "string" ? value.documentId : "",
    documentIdsText: typeof value?.documentIdsText === "string" ? value.documentIdsText : "",
    searchQuery: typeof value?.searchQuery === "string" ? value.searchQuery : "",
    filterTagsText: typeof value?.filterTagsText === "string" ? value.filterTagsText : "",
    filterSourceTypesText: typeof value?.filterSourceTypesText === "string" ? value.filterSourceTypesText : "",
    filterDateFrom: typeof value?.filterDateFrom === "string" ? value.filterDateFrom : "",
    filterDateTo: typeof value?.filterDateTo === "string" ? value.filterDateTo : ""
  };
}
