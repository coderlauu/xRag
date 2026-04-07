import { normalizeWhitespace } from "../common/document-utils";

const DEFAULT_LINK_FETCH_TIMEOUT_MS = 10_000;

export class LinkFetchError extends Error {
  constructor(
    message: string,
    readonly diagnosisCode: "link_fetch_timeout" | "link_fetch_blocked" | "link_extract_empty" | "link_invalid_url",
    readonly httpStatus: number | null = null,
    readonly contentType: string | null = null
  ) {
    super(message);
  }
}

export interface ParsedLinkDocument {
  sourceUrl: string;
  canonicalUrl: string | null;
  contentType: string | null;
  title: string | null;
  text: string;
  parserName: string;
  parserVersion: string;
}

export function extractLinkContent(html: string) {
  const canonicalUrl = matchTagAttribute(html, /<link[^>]+rel=["']canonical["'][^>]*href=["']([^"']+)["'][^>]*>/i);
  const title = decodeHtmlEntities(matchTagContent(html, /<title[^>]*>([\s\S]*?)<\/title>/i));
  const bodyHtml = matchTagContent(html, /<body[^>]*>([\s\S]*?)<\/body>/i) ?? html;

  const withoutNoise = bodyHtml
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<noscript\b[^>]*>[\s\S]*?<\/noscript>/gi, " ");

  const withLineBreaks = withoutNoise
    .replace(/<(br|\/p|\/div|\/section|\/article|\/li|\/h\d|\/tr)\b[^>]*>/gi, "\n")
    .replace(/<(p|div|section|article|li|h\d|tr)\b[^>]*>/gi, "\n");
  const text = normalizeWhitespace(decodeHtmlEntities(withLineBreaks.replace(/<[^>]+>/g, " ")));

  if (!text) {
    throw new LinkFetchError("Link extraction returned empty text", "link_extract_empty");
  }

  return {
    canonicalUrl: canonicalUrl ? canonicalUrl.trim() : null,
    title: title ? normalizeWhitespace(title) : null,
    text
  };
}

export async function fetchAndExtractLinkDocument(
  rawUrl: string,
  fetchImpl: typeof fetch = fetch
): Promise<ParsedLinkDocument> {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new LinkFetchError("Invalid link url", "link_invalid_url");
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), DEFAULT_LINK_FETCH_TIMEOUT_MS);

  try {
    const response = await fetchImpl(url.toString(), {
      redirect: "follow",
      headers: {
        accept: "text/html, text/plain;q=0.9, */*;q=0.1",
        "user-agent": "xRag-LinkFetcher/1.0"
      },
      signal: controller.signal
    });

    const contentType = response.headers.get("content-type");
    if (!response.ok) {
      throw new LinkFetchError(
        `Link fetch failed with status ${response.status}`,
        response.status === 401 || response.status === 403 || response.status === 429 ? "link_fetch_blocked" : "link_fetch_timeout",
        response.status,
        contentType
      );
    }

    const html = await response.text();
    const extracted = extractLinkContent(html);
    return {
      sourceUrl: url.toString(),
      canonicalUrl: extracted.canonicalUrl,
      contentType,
      title: extracted.title,
      text: extracted.text,
      parserName: "link-fetcher",
      parserVersion: "1.0.0"
    };
  } catch (error) {
    if (error instanceof LinkFetchError) {
      throw error;
    }

    if (error instanceof Error && error.name === "AbortError") {
      throw new LinkFetchError("Link fetch timed out", "link_fetch_timeout");
    }

    throw new LinkFetchError(
      error instanceof Error ? error.message : "Link fetch failed",
      "link_fetch_timeout"
    );
  } finally {
    clearTimeout(timeout);
  }
}

function matchTagContent(input: string, pattern: RegExp): string | null {
  const match = input.match(pattern);
  return match?.[1] ?? null;
}

function matchTagAttribute(input: string, pattern: RegExp): string | null {
  const match = input.match(pattern);
  return match?.[1] ?? null;
}

function decodeHtmlEntities(input: string | null): string {
  if (!input) {
    return "";
  }

  return input
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&#(\d+);/g, (_, value) => String.fromCharCode(Number.parseInt(value, 10)));
}
