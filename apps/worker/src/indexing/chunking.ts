import { createHash } from "node:crypto";

export interface DocumentChunkDraft {
  chunkIndex: number;
  strategyVersion: string;
  sectionLabel: string | null;
  pageRef: string | null;
  contentText: string;
  tokenCount: number;
  contentSha256: string;
  citationLocator: Record<string, unknown>;
}

const DEFAULT_MAX_CHUNK_CHARS = 1800;
const DEFAULT_SPLIT_OVERLAP_CHARS = 200;
const DEFAULT_TOKEN_APPROXIMATION_CHARS = 4;

export function buildIndexVersion(model: string, dimensions = 1536): string {
  const normalizedModel = model.trim();
  if (!normalizedModel) {
    throw new Error("embedding model is required");
  }

  return `phase-2a-index-v1:${normalizedModel}:${dimensions}`;
}

export function chunkDocumentText(input: {
  documentId: string;
  title: string;
  text: string;
  strategyVersion: string;
  maxChunkChars?: number;
  splitOverlapChars?: number;
}): DocumentChunkDraft[] {
  const normalizedText = normalizeSourceText(input.text);
  if (!normalizedText) {
    return [];
  }

  const maxChunkChars = input.maxChunkChars ?? DEFAULT_MAX_CHUNK_CHARS;
  const splitOverlapChars = Math.min(input.splitOverlapChars ?? DEFAULT_SPLIT_OVERLAP_CHARS, maxChunkChars - 1);
  const paragraphs = splitParagraphs(normalizedText);
  const chunks: Array<{
    contentParts: string[];
    sectionLabel: string | null;
  }> = [];

  let currentParts: string[] = [];
  let currentLength = 0;
  let currentSectionLabel: string | null = null;
  let activeSectionLabel: string | null = null;

  const flushCurrent = () => {
    if (currentParts.length === 0) {
      return;
    }

    chunks.push({
      contentParts: currentParts,
      sectionLabel: currentSectionLabel ?? activeSectionLabel
    });

    currentParts = [];
    currentLength = 0;
  };

  for (const rawParagraph of paragraphs) {
    const heading = extractHeading(rawParagraph);
    if (heading) {
      activeSectionLabel = heading;
    }

    const paragraphPieces = splitLongParagraph(rawParagraph, maxChunkChars, splitOverlapChars);
    for (const piece of paragraphPieces) {
      const pieceLength = piece.length;
      if (currentParts.length > 0 && currentLength + 2 + pieceLength > maxChunkChars) {
        flushCurrent();
        currentSectionLabel = activeSectionLabel;
      }

      if (currentParts.length === 0) {
        currentSectionLabel = activeSectionLabel;
      }

      currentParts.push(piece);
      currentLength += pieceLength + (currentParts.length > 1 ? 2 : 0);
    }
  }

  flushCurrent();

  return chunks.map((chunk, chunkIndex) => {
    const contentText = chunk.contentParts.join("\n\n").trim();
    return {
      chunkIndex,
      strategyVersion: input.strategyVersion,
      sectionLabel: chunk.sectionLabel,
      pageRef: null,
      contentText,
      tokenCount: estimateTokenCount(contentText),
      contentSha256: sha256(contentText),
      citationLocator: {
        chunk_index: chunkIndex,
        strategy_version: input.strategyVersion,
        chunk_title: input.title,
        token_count: estimateTokenCount(contentText),
        content_sha256: sha256(contentText),
        document_id: input.documentId
      }
    };
  });
}

function normalizeSourceText(value: string): string {
  return value.replace(/\r\n/g, "\n").trim();
}

function splitParagraphs(value: string): string[] {
  return value
    .split(/\n{2,}/)
    .flatMap((paragraph) => paragraph.split(/\n+/))
    .map((paragraph) => paragraph.replace(/\s+/g, " ").trim())
    .filter(Boolean);
}

function extractHeading(value: string): string | null {
  const markdownHeading = value.match(/^#{1,6}\s+(.+)$/);
  if (markdownHeading?.[1]) {
    return markdownHeading[1].trim();
  }

  return null;
}

function splitLongParagraph(value: string, maxChunkChars: number, splitOverlapChars: number): string[] {
  if (value.length <= maxChunkChars) {
    return [value];
  }

  const pieces: string[] = [];
  let start = 0;

  while (start < value.length) {
    const end = Math.min(start + maxChunkChars, value.length);
    const piece = value.slice(start, end).trim();
    if (piece) {
      pieces.push(piece);
    }

    if (end >= value.length) {
      break;
    }

    start = Math.max(0, end - splitOverlapChars);
  }

  return pieces;
}

function estimateTokenCount(value: string): number {
  if (!value.trim()) {
    return 0;
  }

  return Math.max(1, Math.ceil(value.length / DEFAULT_TOKEN_APPROXIMATION_CHARS));
}

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}
