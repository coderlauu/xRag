import { normalizeWhitespace } from "../common/document-utils";
import type { AnswerRepository, RetrievalChunkCandidateRecord, RetrievalRunRecord, RetrievalTraceHitRecord } from "../database/answer-repository";
import type { EmbeddingProvider } from "../providers";

export interface HybridRetrieverDependencies {
  repository: AnswerRepository;
  embeddingProvider: EmbeddingProvider;
}

export interface HybridRetrievalInput {
  sessionId: string;
  question: string;
  scopeDocumentIds: string[] | null;
}

export interface HybridRetrievalOutcome {
  queryNormalized: string;
  eligibleDocumentCount: number;
  lexicalHitCount: number;
  semanticHitCount: number;
  mergedHitCount: number;
  retrievalRun: RetrievalRunRecord;
  candidatePack: RetrievalChunkCandidateRecord[];
  traceHits: RetrievalTraceHitRecord[];
  diagnosisCode: "retrieval_no_hits" | "retrieval_scope_empty" | null;
  refusalReason: string | null;
}

const LEXICAL_LIMIT = 12;
const SEMANTIC_LIMIT = 12;
const PACK_LIMIT = 8;
const TRACE_LIMIT = 20;

export class HybridRetriever {
  constructor(private readonly deps: HybridRetrieverDependencies) {}

  async retrieve(input: HybridRetrievalInput): Promise<HybridRetrievalOutcome> {
    const queryNormalized = normalizeWhitespace(input.question);
    const eligibleDocumentCount = await this.deps.repository.countEligibleDocuments(input.scopeDocumentIds);

    if (eligibleDocumentCount === 0) {
      const diagnosisCode = input.scopeDocumentIds && input.scopeDocumentIds.length > 0
        ? "retrieval_scope_empty"
        : "retrieval_no_hits";
      const refusalReason =
        diagnosisCode === "retrieval_scope_empty"
          ? "当前范围内没有可引用的已索引文档，请先完成索引或扩大问答范围。"
          : "当前知识库中没有检索到足够相关的可引用证据。";
      const retrievalRun = await this.deps.repository.createRetrievalRun({
        sessionId: input.sessionId,
        queryNormalized,
        eligibleDocumentCount,
        lexicalHitCount: 0,
        semanticHitCount: 0,
        mergedHitCount: 0,
        rerankStrategy: "hybrid",
        latencyMs: 0
      });

      return {
        queryNormalized,
        eligibleDocumentCount,
        lexicalHitCount: 0,
        semanticHitCount: 0,
        mergedHitCount: 0,
        retrievalRun,
        candidatePack: [],
        traceHits: [],
        diagnosisCode,
        refusalReason
      };
    }

    const startedAt = Date.now();
    const lexicalPromise = this.deps.repository.listLexicalChunkCandidates(
      queryNormalized,
      input.scopeDocumentIds,
      LEXICAL_LIMIT
    );
    const embeddingPromise = this.deps.embeddingProvider.embed(queryNormalized);

    const [lexicalCandidates, embeddingResult] = await Promise.all([lexicalPromise, embeddingPromise]);
    const queryVector = embeddingResult.vectors[0];

    if (!queryVector || queryVector.length === 0) {
      throw new Error("embedding provider returned an empty query vector");
    }

    const semanticCandidates = await this.deps.repository.listSemanticChunkCandidates(
      queryVector,
      input.scopeDocumentIds,
      SEMANTIC_LIMIT
    );

    const mergedCandidates = mergeCandidates(lexicalCandidates, semanticCandidates).slice(0, TRACE_LIMIT);
    const candidatePack = mergedCandidates.slice(0, PACK_LIMIT);
    const traceHits = mergedCandidates.map((candidate, index) => ({
      retrievalRunId: "",
      documentId: candidate.documentId,
      chunkId: candidate.id,
      rank: index + 1,
      lexicalScore: candidate.lexicalScore,
      semanticScore: candidate.semanticScore,
      finalScore: candidate.finalScore,
      usedInAnswer: false,
      exclusionReason: index < PACK_LIMIT ? null : "pack_limit"
    }));

    const retrievalRun = await this.deps.repository.createRetrievalRun({
      sessionId: input.sessionId,
      queryNormalized,
      eligibleDocumentCount,
      lexicalHitCount: lexicalCandidates.length,
      semanticHitCount: semanticCandidates.length,
      mergedHitCount: mergedCandidates.length,
      rerankStrategy: "hybrid",
      latencyMs: Date.now() - startedAt
    });

    await this.deps.repository.insertRetrievalRunHits(
      traceHits.map((hit) => ({
        ...hit,
        retrievalRunId: retrievalRun.id
      }))
    );

    return {
      queryNormalized,
      eligibleDocumentCount,
      lexicalHitCount: lexicalCandidates.length,
      semanticHitCount: semanticCandidates.length,
      mergedHitCount: mergedCandidates.length,
      retrievalRun,
      candidatePack,
      traceHits,
      diagnosisCode: candidatePack.length === 0 ? "retrieval_no_hits" : null,
      refusalReason:
        candidatePack.length === 0
          ? "当前范围内没有检索到足够相关的可引用证据。"
          : null
    };
  }
}

function mergeCandidates(
  lexicalCandidates: RetrievalChunkCandidateRecord[],
  semanticCandidates: RetrievalChunkCandidateRecord[]
): RetrievalChunkCandidateRecord[] {
  const merged = new Map<string, RetrievalChunkCandidateRecord>();

  for (const candidate of lexicalCandidates) {
    merged.set(candidate.id, {
      ...candidate,
      finalScore: candidate.lexicalScore ?? candidate.finalScore ?? 0
    });
  }

  for (const candidate of semanticCandidates) {
    const existing = merged.get(candidate.id);
    if (!existing) {
      merged.set(candidate.id, {
        ...candidate,
        finalScore: candidate.semanticScore ?? candidate.finalScore ?? 0
      });
      continue;
    }

    const lexicalScore = existing.lexicalScore ?? candidate.lexicalScore;
    const semanticScore = existing.semanticScore ?? candidate.semanticScore;
    merged.set(candidate.id, {
      ...existing,
      lexicalScore,
      semanticScore,
      finalScore: scoreCandidate(lexicalScore, semanticScore)
    });
  }

  return Array.from(merged.values()).sort((left, right) => {
    const scoreDiff = (right.finalScore ?? 0) - (left.finalScore ?? 0);
    if (scoreDiff !== 0) {
      return scoreDiff;
    }

    if (left.documentTitle !== right.documentTitle) {
      return left.documentTitle.localeCompare(right.documentTitle);
    }

    return left.chunkIndex - right.chunkIndex;
  });
}

function scoreCandidate(lexicalScore: number | null, semanticScore: number | null): number {
  if (lexicalScore !== null && semanticScore !== null) {
    return Number((lexicalScore * 0.45 + semanticScore * 0.55).toFixed(4));
  }

  if (semanticScore !== null) {
    return semanticScore;
  }

  if (lexicalScore !== null) {
    return lexicalScore;
  }

  return 0;
}
