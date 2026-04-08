import type { AnswerOrchestrationJobData, AnswerOrchestrationJobName } from "../queue/constants";
import type { Logger } from "../logging/logger";
import type { AnswerProvider, AnswerProviderResult, EmbeddingProvider } from "../providers";
import { ProviderRequestError } from "../providers";
import type {
  AnswerChunkRecord,
  AnswerRepository,
  AnswerSessionRecord,
  RetrievalChunkCandidateRecord
} from "../database/answer-repository";
import { HybridRetriever, type HybridRetrievalOutcome } from "../retrieval/hybrid-retriever";
import { normalizeWhitespace } from "../common/document-utils";

export interface AnswerOrchestrationJobResult {
  sessionId: string;
  status: "success" | "failed" | "skipped";
  reason?: string;
}

export interface AnswerOrchestrationDependencies {
  repository: AnswerRepository;
  getEmbeddingProvider: () => EmbeddingProvider;
  getAnswerProvider: () => AnswerProvider;
  logger: Logger;
}

interface AnswerOrchestrationModelResponse {
  decision: "answered" | "needs_scope" | "refused";
  answer_summary?: string | null;
  refusal_reason?: string | null;
  scope_suggestions?: string[] | null;
  supporting_chunk_ids?: string[] | null;
}

export function createAnswerOrchestrationHandlers(deps: AnswerOrchestrationDependencies) {
  return {
    answer_session: (context: JobContext) => processAnswerSession(context, deps)
  } satisfies Record<AnswerOrchestrationJobName, (context: JobContext) => Promise<AnswerOrchestrationJobResult>>;
}

type JobContext = {
  name: AnswerOrchestrationJobName;
  id: string;
  data: AnswerOrchestrationJobData;
  attemptsMade: number;
};

async function processAnswerSession(
  context: JobContext,
  deps: AnswerOrchestrationDependencies
): Promise<AnswerOrchestrationJobResult> {
  const { repository, getEmbeddingProvider, getAnswerProvider, logger } = deps;
  const session = await repository.getAnswerSessionById(context.data.sessionId);

  if (!session) {
    logger.warn("answer session not found", {
      queueJobId: context.id,
      sessionId: context.data.sessionId
    });

    return {
      sessionId: context.data.sessionId,
      status: "skipped",
      reason: "answer session not found"
    };
  }

  if (isTerminalStatus(session.status)) {
    logger.info("answer session already completed", {
      queueJobId: context.id,
      sessionId: session.id,
      status: session.status
    });

    return {
      sessionId: session.id,
      status: "skipped",
      reason: `answer session already ${session.status}`
    };
  }

  await repository.markAnswerSessionRetrieving(session.id, context.id);

  try {
    const scopeDocumentIds = resolveScopeDocumentIds(session);
    const retriever = new HybridRetriever({
      repository,
      embeddingProvider: getEmbeddingProvider()
    });
    const retrieval = await retriever.retrieve({
      sessionId: session.id,
      question: session.question,
      scopeDocumentIds
    });

    if (retrieval.diagnosisCode || retrieval.candidatePack.length === 0) {
      await repository.markAnswerSessionRefused(session.id, {
        refusalReason: retrieval.refusalReason ?? "当前范围内没有可用证据。",
        diagnosisCode: retrieval.diagnosisCode,
        providerName: null,
        providerModel: null,
        latencyMs: endToEndLatencyMs(session),
        promptTokens: null,
        completionTokens: null,
        totalCostUsd: null
      });

      return {
        sessionId: session.id,
        status: "success",
        reason: retrieval.diagnosisCode ?? "retrieval produced no evidence"
      };
    }

    await repository.markAnswerSessionSynthesizing(session.id);
    const answerResult = await generateGroundedAnswer(getAnswerProvider(), session, retrieval);
    const validated = validateAnswerResponse(answerResult, retrieval.candidatePack);

    if (validated.decision === "answered") {
      await persistAnsweredSession(repository, session, retrieval, answerResult, {
        answerSummary: validated.answerSummary ?? "",
        supportingChunkIds: validated.supportingChunkIds
      });
      return {
        sessionId: session.id,
        status: "success"
      };
    }

    if (validated.decision === "needs_scope") {
      await repository.markAnswerSessionNeedsScope(session.id, {
        refusalReason: formatScopeSuggestions(
          validated.scopeSuggestions.length > 0
            ? validated.scopeSuggestions
            : buildDefaultScopeSuggestions(session),
          validated.refusalReason
        ),
        diagnosisCode: null,
        providerName: answerResult.providerName,
        providerModel: answerResult.model,
        latencyMs: endToEndLatencyMs(session),
        promptTokens: answerResult.usage?.promptTokens ?? null,
        completionTokens: answerResult.usage?.completionTokens ?? null,
        totalCostUsd: null
      });

      return {
        sessionId: session.id,
        status: "success",
        reason: "needs_scope"
      };
    }

    await repository.markAnswerSessionRefused(session.id, {
      refusalReason: validated.refusalReason ?? "当前范围内证据不足，无法安全回答。",
      diagnosisCode: validated.diagnosisCode,
      providerName: answerResult.providerName,
      providerModel: answerResult.model,
      latencyMs: endToEndLatencyMs(session),
      promptTokens: answerResult.usage?.promptTokens ?? null,
      completionTokens: answerResult.usage?.completionTokens ?? null,
      totalCostUsd: null
    });

    return {
      sessionId: session.id,
      status: "success",
      reason: "refused"
    };
  } catch (error) {
    const diagnosisCode = mapProviderFailureDiagnosisCode(error);
    const message = error instanceof Error ? error.message : "answer orchestration failed";

    await repository.markAnswerSessionFailed(session.id, {
      refusalReason: message,
      diagnosisCode,
      providerName: null,
      providerModel: null,
      latencyMs: endToEndLatencyMs(session),
      promptTokens: null,
      completionTokens: null,
      totalCostUsd: null
    });

    logger.error("answer orchestration failed", {
      queueJobId: context.id,
      sessionId: session.id,
      errorMessage: message,
      diagnosisCode
    });

    return {
      sessionId: session.id,
      status: "failed",
      reason: message
    };
  }
}

async function generateGroundedAnswer(
  answerProvider: AnswerProvider,
  session: AnswerSessionRecord,
  retrieval: HybridRetrievalOutcome
): Promise<AnswerProviderResult> {
  const messages = [
    {
      role: "system" as const,
      content: [
        "You are a grounded answer orchestrator for a private knowledge base.",
        "Use only the evidence pack below.",
        "Return JSON only.",
        "decision must be one of: answered, needs_scope, refused.",
        "When decision is answered, include a concise answer_summary and supporting_chunk_ids.",
        "When decision is needs_scope, include actionable scope_suggestions.",
        "When decision is refused, include a clear refusal_reason.",
        "Do not invent chunk ids or cite evidence that is not in the pack."
      ].join("\n")
    },
    {
      role: "user" as const,
      content: JSON.stringify(
        {
          question: session.question,
          scope: {
            mode: session.scopeMode,
            payload: session.scopePayload
          },
          retrieval: {
            query_normalized: retrieval.queryNormalized,
            eligible_document_count: retrieval.eligibleDocumentCount,
            lexical_hit_count: retrieval.lexicalHitCount,
            semantic_hit_count: retrieval.semanticHitCount,
            merged_hit_count: retrieval.mergedHitCount
          },
          evidence_pack: retrieval.candidatePack.map((candidate) => ({
            chunk_id: candidate.id,
            document_id: candidate.documentId,
            document_title: candidate.documentTitle,
            chunk_index: candidate.chunkIndex,
            section_label: candidate.sectionLabel,
            page_ref: candidate.pageRef,
            lexical_score: candidate.lexicalScore,
            semantic_score: candidate.semanticScore,
            final_score: candidate.finalScore,
            excerpt: buildEvidenceExcerpt(candidate)
          }))
        },
        null,
        2
      )
    }
  ];

  return answerProvider.generate({
    messages,
    temperature: 0.1,
    maxOutputTokens: 768,
    responseFormat: "json_object"
  });
}

function validateAnswerResponse(
  result: AnswerProviderResult,
  candidatePack: RetrievalChunkCandidateRecord[]
): {
  decision: "answered" | "needs_scope" | "refused";
  answerSummary: string | null;
  refusalReason: string | null;
  scopeSuggestions: string[];
  supportingChunkIds: string[];
  diagnosisCode: "answer_insufficient_evidence" | "citation_missing" | null;
} {
  const parsed = parseStructuredAnswer(result.text);
  if (!parsed) {
    throw new Error("answer provider returned invalid structured output");
  }

  if (!isValidDecision(parsed.decision)) {
    throw new Error(`answer provider returned unsupported decision: ${String(parsed.decision)}`);
  }

  const candidateIds = new Set(candidatePack.map((candidate) => candidate.id));
  const supportingChunkIds = normalizeStringArray(parsed.supporting_chunk_ids);

  if (parsed.decision === "answered") {
    const answerSummary = normalizeTextField(parsed.answer_summary);
    if (!answerSummary) {
      return {
        decision: "refused",
        answerSummary: null,
        refusalReason: "答案摘要为空，无法通过 citation validator。",
        scopeSuggestions: [],
        supportingChunkIds: [],
        diagnosisCode: "answer_insufficient_evidence"
      };
    }

    if (supportingChunkIds.length === 0) {
      return {
        decision: "refused",
        answerSummary: null,
        refusalReason: "缺少支撑该答案的 citation。",
        scopeSuggestions: [],
        supportingChunkIds: [],
        diagnosisCode: "citation_missing"
      };
    }

    const unsupportedChunkId = supportingChunkIds.find((chunkId) => !candidateIds.has(chunkId));
    if (unsupportedChunkId) {
      return {
        decision: "refused",
        answerSummary: null,
        refusalReason: `citation 引用了不在 candidate pack 中的 chunk: ${unsupportedChunkId}`,
        scopeSuggestions: [],
        supportingChunkIds: [],
        diagnosisCode: "citation_missing"
      };
    }

    return {
      decision: "answered",
      answerSummary,
      refusalReason: null,
      scopeSuggestions: [],
      supportingChunkIds,
      diagnosisCode: null
    };
  }

  if (parsed.decision === "needs_scope") {
    const scopeSuggestions = normalizeStringArray(parsed.scope_suggestions);
    return {
      decision: "needs_scope",
      answerSummary: null,
      refusalReason: normalizeTextField(parsed.refusal_reason) || null,
      scopeSuggestions,
      supportingChunkIds: [],
      diagnosisCode: null
    };
  }

  return {
    decision: "refused",
    answerSummary: null,
    refusalReason: normalizeTextField(parsed.refusal_reason) || "当前范围内证据不足，无法安全回答。",
    scopeSuggestions: [],
    supportingChunkIds: [],
    diagnosisCode: "answer_insufficient_evidence"
  };
}

async function persistAnsweredSession(
  repository: AnswerRepository,
  session: AnswerSessionRecord,
  retrieval: HybridRetrievalOutcome,
  answerResult: AnswerProviderResult,
  validated: {
    answerSummary: string;
    supportingChunkIds: string[];
  }
): Promise<void> {
  const chunkRecords = await repository.listChunksByIds(validated.supportingChunkIds);
  const citations = buildAnswerCitations(session.id, chunkRecords, validated.supportingChunkIds);

  await repository.withTransaction(async (db) => {
    await repository.insertAnswerCitations(citations, db);
    await repository.markRetrievalHitsUsedInAnswer(retrieval.retrievalRun.id, validated.supportingChunkIds, db);
    await repository.markAnswerSessionAnswered(session.id, {
      answerSummary: validated.answerSummary,
      providerName: answerResult.providerName,
      providerModel: answerResult.model,
      latencyMs: endToEndLatencyMs(session),
      promptTokens: answerResult.usage?.promptTokens ?? null,
      completionTokens: answerResult.usage?.completionTokens ?? null,
      totalCostUsd: null
    }, db);
  });
}

function buildAnswerCitations(
  sessionId: string,
  chunkRecords: AnswerChunkRecord[],
  supportingChunkIds: string[]
) {
  const byChunkId = new Map(chunkRecords.map((record) => [record.id, record]));

  return supportingChunkIds.map((chunkId, index) => {
    const record = byChunkId.get(chunkId);
    if (!record) {
      throw new Error(`missing chunk record for citation: ${chunkId}`);
    }

    return {
      sessionId,
      documentId: record.documentId,
      chunkId: record.id,
      claimSlot: `claim_${index + 1}`,
      quoteText: extractQuoteText(record.contentText),
      locator: record.citationLocator
    };
  });
}

function extractQuoteText(contentText: string): string {
  const collapsed = normalizeWhitespace(contentText);
  if (collapsed.length <= 240) {
    return collapsed;
  }

  const target = collapsed.slice(0, 240);
  const sentenceBreak = Math.max(target.lastIndexOf("。"), target.lastIndexOf("."), target.lastIndexOf("!"), target.lastIndexOf("?"));
  if (sentenceBreak >= 80) {
    return target.slice(0, sentenceBreak + 1).trim();
  }

  return target.trim();
}

function formatScopeSuggestions(scopeSuggestions: string[], refusalReason: string | null): string {
  const lines = ["当前范围需要进一步收窄。"];
  if (refusalReason) {
    lines.push(refusalReason.trim());
  }

  if (scopeSuggestions.length > 0) {
    lines.push("建议：");
    for (const suggestion of scopeSuggestions) {
      lines.push(`- ${suggestion.trim()}`);
    }
  }

  return lines.join("\n");
}

function buildEvidenceExcerpt(candidate: RetrievalChunkCandidateRecord): string {
  return extractQuoteText(candidate.contentText);
}

function parseStructuredAnswer(text: string): AnswerOrchestrationModelResponse | null {
  const trimmed = text.trim();
  const rawJson = extractJsonObject(trimmed);
  if (!rawJson) {
    return null;
  }

  try {
    const parsed = JSON.parse(rawJson) as Partial<AnswerOrchestrationModelResponse>;
    return {
      decision: parsed.decision as AnswerOrchestrationModelResponse["decision"],
      answer_summary: parsed.answer_summary ?? null,
      refusal_reason: parsed.refusal_reason ?? null,
      scope_suggestions: Array.isArray(parsed.scope_suggestions) ? parsed.scope_suggestions : null,
      supporting_chunk_ids: Array.isArray(parsed.supporting_chunk_ids) ? parsed.supporting_chunk_ids : null
    };
  } catch {
    return null;
  }
}

function extractJsonObject(text: string): string | null {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end < 0 || end <= start) {
    return null;
  }

  return text.slice(start, end + 1);
}

function normalizeStringArray(values: string[] | null | undefined): string[] {
  if (!Array.isArray(values)) {
    return [];
  }

  return values
    .map((value) => normalizeTextField(value))
    .filter((value): value is string => Boolean(value));
}

function normalizeTextField(value: string | null | undefined): string | null {
  if (typeof value !== "string") {
    return null;
  }

  const normalized = normalizeWhitespace(value);
  return normalized.length > 0 ? normalized : null;
}

function isValidDecision(value: unknown): value is AnswerOrchestrationModelResponse["decision"] {
  return value === "answered" || value === "needs_scope" || value === "refused";
}

function isTerminalStatus(status: AnswerSessionRecord["status"]): boolean {
  return status === "answered" || status === "needs_scope" || status === "refused" || status === "failed";
}

function resolveScopeDocumentIds(session: AnswerSessionRecord): string[] | null {
  if (session.scopeMode === "global") {
    return null;
  }

  if (session.scopeMode === "document") {
    const documentId = getStringValue(session.scopePayload, "document_id");
    return documentId ? [documentId] : [];
  }

  const documentIds = getStringArrayValue(session.scopePayload, "document_ids");
  if (documentIds.length === 0) {
    return [];
  }

  return Array.from(new Set(documentIds.map((value) => value.trim()).filter(Boolean))).slice(0, 100);
}

function getStringValue(payload: Record<string, unknown> | null, key: string): string | null {
  if (!payload) {
    return null;
  }

  const value = payload[key];
  if (typeof value !== "string") {
    return null;
  }

  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function getStringArrayValue(payload: Record<string, unknown> | null, key: string): string[] {
  if (!payload) {
    return [];
  }

  const value = payload[key];
  if (!Array.isArray(value)) {
    return [];
  }

  return value.filter((item): item is string => typeof item === "string");
}

function mapProviderFailureDiagnosisCode(error: unknown): "provider_timeout" | null {
  if (error && typeof error === "object" && "name" in error && error.name === "AbortError") {
    return "provider_timeout";
  }

  if (error instanceof Error) {
    const normalized = error.message.toLowerCase();
    if (normalized.includes("timeout") || normalized.includes("aborted")) {
      return "provider_timeout";
    }

    if (error instanceof ProviderRequestError && (error.statusCode === 408 || error.statusCode === 504)) {
      return "provider_timeout";
    }
  }

  return null;
}

function endToEndLatencyMs(session: AnswerSessionRecord): number {
  return Math.max(0, Date.now() - new Date(session.createdAt).getTime());
}

function buildDefaultScopeSuggestions(session: AnswerSessionRecord): string[] {
  if (session.scopeMode === "global") {
    return [
      "限定到一篇更具体的文档。",
      "限定到当前搜索结果中的少量文档。",
      "先补充更明确的关键词，再重新提问。"
    ];
  }

  if (session.scopeMode === "document") {
    return [
      "确认目标文档已经完成索引并可引用。",
      "如果问题需要对比，请改为搜索结果范围后再问。"
    ];
  }

  return [
    "缩小 document_ids 的范围，只保留最相关的少量文档。",
    "如果当前结果已经裁剪，请先重新搜索再发起问答。"
  ];
}
