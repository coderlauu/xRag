import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type {
  AnswerClaimFreshnessBadge,
  AnswerRetrievalTraceResponse,
  AnswerScope,
  AnswerSessionResponse,
  CreateAnswerResponse,
  DiagnosisCode,
  ListAnswerSessionsResponse,
  RetrievalExclusionReason,
  ScopeFilterSet,
  SourceType
} from "@xrag/shared-types";
import { normalizeWhitespace } from "../common/document-utils";
import { QueueService } from "../queue/queue.service";
import { AnswerScopeDto, CreateAnswerRequestDto, ListAnswerSessionsQueryDto } from "./answers.dto";
import { AnswersRepository } from "./answers.repository";

const SOURCE_TYPE_VALUES: SourceType[] = ["text", "file", "pdf", "link"];
const RETRIEVAL_EXCLUSION_REASON_VALUES: RetrievalExclusionReason[] = [
  "deduplicated",
  "rerank_cutoff",
  "answer_budget",
  "low_support",
  "citation_unready"
];

@Injectable()
export class AnswersService {
  constructor(
    private readonly answersRepository: AnswersRepository,
    private readonly queueService: QueueService
  ) {}

  async listAnswers(query: ListAnswerSessionsQueryDto): Promise<ListAnswerSessionsResponse> {
    const page = query.page ?? 1;
    const pageSize = query.page_size ?? 20;
    const [sessions, total] = await Promise.all([
      this.answersRepository.listAnswerSessions(page, pageSize),
      this.answersRepository.countAnswerSessions()
    ]);

    return {
      items: sessions.map((session) => ({
        session_id: session.id,
        question: session.question,
        scope: toAnswerScope(session.scopeMode, session.scopePayload),
        scope_summary: summarizeAnswerScope(toAnswerScope(session.scopeMode, session.scopePayload)),
        continued_from_session_id: session.continuedFromSessionId,
        status: session.status,
        updated_at: session.updatedAt.toISOString()
      })),
      page,
      page_size: pageSize,
      total
    };
  }

  async createAnswer(body: CreateAnswerRequestDto): Promise<CreateAnswerResponse> {
    const question = normalizeWhitespace(body.question);
    const scope = this.normalizeScope(body.scope);
    const continuedFromSessionId = normalizeOptionalUuid(body.continued_from_session_id);

    const session = await this.answersRepository.createAnswerSession({
      id: randomUUID(),
      queueJobId: null,
      continuedFromSessionId,
      question,
      scopeMode: scope.mode,
      scopePayload: scope.payload,
      retrievalMode: "hybrid",
      status: "idle"
    });

    try {
      const queueJobId = await this.queueService.enqueueAnswerSession(session.id);
      await this.answersRepository.updateAnswerSession(session.id, {
        queueJobId,
        status: "retrieving"
      });

      return {
        session_id: session.id,
        status: "retrieving"
      };
    } catch (error) {
      await this.answersRepository.updateAnswerSession(session.id, {
        status: "failed",
        diagnosisCode: "queue_backlog",
        refusalReason: null
      });
      throw new BadRequestException(error instanceof Error ? error.message : "Failed to enqueue answer session");
    }
  }

  async getAnswer(sessionId: string): Promise<AnswerSessionResponse> {
    const session = await this.answersRepository.getAnswerSessionById(sessionId);
    if (!session) {
      throw new NotFoundException("Answer session not found");
    }

    const [citations, claims] = await Promise.all([
      this.answersRepository.listCitationsBySessionId(sessionId),
      this.answersRepository.listClaimsBySessionId(sessionId)
    ]);

    const mappedCitations = citations.map((citation) => ({
      document_id: citation.documentId,
      chunk_id: citation.chunkId,
      quote_text: citation.quoteText,
      locator: citation.locator ?? null
    }));

    return {
      session_id: session.id,
      question: session.question,
      scope: toAnswerScope(session.scopeMode, session.scopePayload),
      scope_summary: summarizeAnswerScope(toAnswerScope(session.scopeMode, session.scopePayload)),
      continued_from_session_id: session.continuedFromSessionId,
      status: session.status,
      answer_summary: session.answerSummary,
      refusal_reason: session.refusalReason,
      diagnosis_code: (session.diagnosisCode as DiagnosisCode | null) ?? null,
      retrieval_mode: session.retrievalMode,
      citations: mappedCitations,
      evidence_groups: claims.map((claim) => ({
        claim_slot: claim.claimSlot,
        claim_text: claim.claimText,
        freshness_badge: claim.freshnessBadge as AnswerClaimFreshnessBadge,
        citations: mappedCitations.filter((citation) =>
          citations.some(
            (source) =>
              source.claimSlot === claim.claimSlot &&
              source.documentId === citation.document_id &&
              source.chunkId === citation.chunk_id
          )
        )
      })),
      latency_ms: session.latencyMs,
      total_cost_usd: session.totalCostUsd ? String(session.totalCostUsd) : null,
      updated_at: session.updatedAt.toISOString()
    };
  }

  async getAnswerRetrieval(sessionId: string): Promise<AnswerRetrievalTraceResponse> {
    const session = await this.answersRepository.getAnswerSessionById(sessionId);
    if (!session) {
      throw new NotFoundException("Answer session not found");
    }

    const retrievalRun = await this.answersRepository.getLatestRetrievalRunBySessionId(sessionId);
    if (!retrievalRun) {
      return {
        session_id: sessionId,
        summary: null,
        items: []
      };
    }

    const items = await this.answersRepository.listRetrievalHitsByRunId(retrievalRun.id);
    return {
      session_id: sessionId,
      summary: {
        query_normalized: retrievalRun.queryNormalized,
        eligible_document_count: retrievalRun.eligibleDocumentCount,
        lexical_hit_count: retrievalRun.lexicalHitCount,
        semantic_hit_count: retrievalRun.semanticHitCount,
        merged_hit_count: retrievalRun.mergedHitCount,
        rerank_strategy: "hybrid",
        latency_ms: retrievalRun.latencyMs
      },
      items: items.map((item) => ({
        document_id: item.documentId,
        chunk_id: item.chunkId,
        rank: item.rank,
        lexical_score: item.lexicalScore === null ? null : Number(item.lexicalScore),
        semantic_score: item.semanticScore === null ? null : Number(item.semanticScore),
        final_score: item.finalScore === null ? null : Number(item.finalScore),
        used_in_answer: item.usedInAnswer,
        exclusion_reason: normalizeExclusionReason(item.exclusionReason)
      }))
    };
  }

  private normalizeScope(scope: AnswerScopeDto): AnswerScope {
    if (scope.mode === "global") {
      const filters = normalizeScopeFilters(scope.payload?.filters);
      return {
        mode: "global",
        payload: filters ? { filters } : null
      };
    }

    if (scope.mode === "document") {
      const documentId = scope.payload?.document_id;
      if (typeof documentId !== "string" || documentId.trim().length === 0) {
        throw new BadRequestException("document scope requires payload.document_id");
      }

      return {
        mode: "document",
        payload: {
          document_id: documentId.trim()
        }
      };
    }

    const documentIds = scope.payload?.document_ids;
    const truncated = scope.payload?.truncated;
    const filters = normalizeScopeFilters(scope.payload?.filters);
    const query = normalizeOptionalString(scope.payload?.query);

    if (!Array.isArray(documentIds) || documentIds.some((value) => typeof value !== "string")) {
      throw new BadRequestException("search_result scope requires payload.document_ids");
    }

    const normalizedDocumentIds = Array.from(
      new Set(documentIds.map((value) => value.trim()).filter((value) => value.length > 0))
    );

    if (normalizedDocumentIds.length === 0 || normalizedDocumentIds.length > 100) {
      throw new BadRequestException("search_result scope requires 1-100 document_ids");
    }

    if (typeof truncated !== "boolean") {
      throw new BadRequestException("search_result scope requires payload.truncated");
    }

    return {
      mode: "search_result",
      payload: {
        document_ids: normalizedDocumentIds,
        truncated,
        query,
        filters
      }
    };
  }
}

function normalizeScopeFilters(input: unknown): ScopeFilterSet | undefined {
  if (input === null || input === undefined) {
    return undefined;
  }

  if (!isRecord(input)) {
    throw new BadRequestException("scope filters must be an object");
  }

  const normalized: ScopeFilterSet = {};

  if (input.tags !== undefined) {
    if (!Array.isArray(input.tags) || input.tags.some((value) => typeof value !== "string")) {
      throw new BadRequestException("scope filters.tags must be a string array");
    }

    const tags = Array.from(new Set(input.tags.map((value) => value.trim()).filter((value) => value.length > 0)));
    if (tags.length > 20) {
      throw new BadRequestException("scope filters.tags supports up to 20 values");
    }
    if (tags.length > 0) {
      normalized.tags = tags;
    }
  }

  if (input.source_types !== undefined) {
    if (!Array.isArray(input.source_types) || input.source_types.some((value) => typeof value !== "string")) {
      throw new BadRequestException("scope filters.source_types must be a string array");
    }

    const sourceTypes = Array.from(
      new Set(
        input.source_types.map((value) => value.trim()).filter((value): value is SourceType =>
          SOURCE_TYPE_VALUES.includes(value as SourceType)
        )
      )
    );

    if (sourceTypes.length !== input.source_types.filter((value) => typeof value === "string").length) {
      throw new BadRequestException("scope filters.source_types contains unsupported values");
    }

    if (sourceTypes.length > 4) {
      throw new BadRequestException("scope filters.source_types supports up to 4 values");
    }

    if (sourceTypes.length > 0) {
      normalized.source_types = sourceTypes;
    }
  }

  const dateFrom = normalizeOptionalString(input.date_from);
  const dateTo = normalizeOptionalString(input.date_to);
  if (dateFrom && Number.isNaN(Date.parse(dateFrom))) {
    throw new BadRequestException("scope filters.date_from must be an ISO8601 string");
  }
  if (dateTo && Number.isNaN(Date.parse(dateTo))) {
    throw new BadRequestException("scope filters.date_to must be an ISO8601 string");
  }
  if (dateFrom) {
    normalized.date_from = dateFrom;
  }
  if (dateTo) {
    normalized.date_to = dateTo;
  }
  if (dateFrom && dateTo && new Date(dateFrom).getTime() > new Date(dateTo).getTime()) {
    throw new BadRequestException("scope filters.date_from must be before or equal to date_to");
  }

  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

function summarizeAnswerScope(scope: AnswerScope): string {
  if (scope.mode === "document") {
    return `单文档 · ${scope.payload.document_id}`;
  }

  if (scope.mode === "search_result") {
    const segments = [`搜索结果 · ${scope.payload.document_ids.length} 个文档`];
    if (scope.payload.truncated) {
      segments.push("已截断");
    }
    const filters = summarizeScopeFilters(scope.payload.filters);
    if (filters) {
      segments.push(filters);
    }
    return segments.join("；");
  }

  const filters = summarizeScopeFilters(scope.payload?.filters);
  return filters ? `全库；${filters}` : "全库";
}

function toAnswerScope(mode: AnswerScope["mode"], payload: Record<string, unknown> | null): AnswerScope {
  return {
    mode,
    payload
  } as AnswerScope;
}

function summarizeScopeFilters(filters: ScopeFilterSet | null | undefined): string | null {
  if (!filters) {
    return null;
  }

  const segments: string[] = [];
  if (filters.tags && filters.tags.length > 0) {
    segments.push(`标签 ${filters.tags.join("、")}`);
  }
  if (filters.source_types && filters.source_types.length > 0) {
    segments.push(`来源 ${filters.source_types.join("、")}`);
  }
  if (filters.date_from || filters.date_to) {
    segments.push(`时间 ${filters.date_from ?? "起始未设"} 至 ${filters.date_to ?? "当前"}`);
  }

  return segments.length > 0 ? segments.join("；") : null;
}

function normalizeOptionalString(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }

  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function normalizeOptionalUuid(value: unknown): string | null {
  return normalizeOptionalString(value);
}

function normalizeExclusionReason(value: string | null): RetrievalExclusionReason | null {
  if (!value) {
    return null;
  }

  return RETRIEVAL_EXCLUSION_REASON_VALUES.includes(value as RetrievalExclusionReason)
    ? (value as RetrievalExclusionReason)
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
