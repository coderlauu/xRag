export function normalizeWhitespace(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

export function normalizeTagName(value: string): string {
  return normalizeWhitespace(value).toLowerCase();
}

export function dedupeStrings(values: string[]): string[] {
  return Array.from(new Set(values));
}

export function createContentPreview(content: string | null, maxLength = 180): string {
  const normalized = content ? normalizeWhitespace(content) : "";
  if (!normalized) {
    return "";
  }

  if (normalized.length <= maxLength) {
    return normalized;
  }

  return `${normalized.slice(0, maxLength - 1)}…`;
}

export function buildSearchText(input: {
  title: string;
  contentClean: string | null;
  tags: string[];
  fileName: string | null;
  sourceUrl: string | null;
}): string {
  return [
    input.title,
    input.contentClean || "",
    input.tags.join(" "),
    input.fileName || "",
    input.sourceUrl || ""
  ]
    .map((value) => value.trim())
    .filter(Boolean)
    .join("\n");
}

export function sanitizeFileName(fileName: string): string {
  const trimmed = fileName.trim();
  const withoutSeparators = trimmed.replace(/[\\/]+/g, "-");
  const collapsed = withoutSeparators.replace(/\s+/g, "-");
  const safe = collapsed.replace(/[^a-zA-Z0-9._-]/g, "-");
  return safe.replace(/-+/g, "-");
}

export function toIsoString(value: Date | string): string {
  return value instanceof Date ? value.toISOString() : new Date(value).toISOString();
}

export function parseCommaSeparated(value: string | undefined): string[] {
  if (!value) {
    return [];
  }

  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}
