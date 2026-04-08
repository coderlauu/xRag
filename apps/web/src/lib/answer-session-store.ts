const STORAGE_KEY = "xrag:last-answer-session-id";

export function loadAnswerSessionId(): string | null {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function rememberAnswerSessionId(sessionId: string) {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.sessionStorage.setItem(STORAGE_KEY, sessionId);
  } catch {
    // Ignore storage failures in constrained browser contexts.
  }
}

export function clearAnswerSessionId() {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore storage failures in constrained browser contexts.
  }
}
