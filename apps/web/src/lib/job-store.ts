const STORAGE_KEY = "xrag:last-job-ids";

type JobStore = Record<string, string>;

function readStore(): JobStore {
  if (typeof window === "undefined") {
    return {};
  }

  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as JobStore) : {};
  } catch {
    return {};
  }
}

function writeStore(store: JobStore) {
  if (typeof window === "undefined") {
    return;
  }

  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(store));
}

export function rememberJobId(documentId: string, jobId: string) {
  const store = readStore();
  store[documentId] = jobId;
  writeStore(store);
}

export function loadJobId(documentId: string): string | null {
  const store = readStore();
  return store[documentId] || null;
}
