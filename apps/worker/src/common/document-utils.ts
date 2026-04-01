export function normalizeWhitespace(value: string): string {
  return value.replace(/\s+/g, " ").trim();
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

export function inferTextSupport(mimeType: string | null): { supported: boolean; reason?: string } {
  if (!mimeType) {
    return { supported: false, reason: "missing mime type" };
  }

  if (mimeType.startsWith("text/")) {
    return { supported: true };
  }

  if (mimeType === "application/json" || mimeType === "application/xml") {
    return { supported: true };
  }

  if (mimeType === "application/pdf") {
    return { supported: false, reason: "pdf parsing not wired yet" };
  }

  return { supported: false, reason: `unsupported mime type: ${mimeType}` };
}
