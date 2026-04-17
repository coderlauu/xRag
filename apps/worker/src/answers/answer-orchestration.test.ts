import assert from "node:assert/strict";
import test from "node:test";
import type { AnswerProvider, EmbeddingProvider } from "../providers";
import type { AnswerRepository, AnswerSessionRecord, AnswerChunkRecord, RetrievalChunkCandidateRecord, RetrievalRunRecord } from "../database/answer-repository";
import { createAnswerOrchestrationHandlers } from "./answer-orchestration";
import type { ScopeFilterSet } from "@xrag/shared-types";

test("answer orchestration persists answer claims, citations, and low-support exclusions", async () => {
  const session = createSession();
  const repository = createRepository({
    session,
    eligibleDocumentCount: 1,
    lexicalCandidates: [createCandidate("chunk-1", 0.8, null), createCandidate("chunk-2", 0.6, null)],
    semanticCandidates: [createCandidate("chunk-1", null, 0.9), createCandidate("chunk-2", null, 0.7)],
    chunkRecords: [
      createChunkRecord("chunk-1", "建议优先投入混合检索、引用链路与 freshness。"),
      createChunkRecord("chunk-2", "这个方向重要，但当前证据支持度不足。")
    ]
  });
  const embeddingProvider = createEmbeddingProvider();
  const answerProvider = createAnswerProvider({
    decision: "answered",
    answer_summary: "应优先投入混合检索、引用链路与 freshness。",
    supporting_chunk_ids: ["chunk-1"],
    claims: [
      {
        claim_text: "应优先投入混合检索、引用链路与 freshness。",
        supporting_chunk_ids: ["chunk-1"]
      }
    ]
  });

  const handlers = createAnswerOrchestrationHandlers({
    repository,
    getEmbeddingProvider: () => embeddingProvider,
    getAnswerProvider: () => answerProvider,
    logger: createLogger()
  });

  const result = await handlers.answer_session({
    name: "answer_session",
    id: "queue-job-1",
    data: {
      sessionId: session.id
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(session.status, "answered");
  assert.equal(session.answerSummary, "应优先投入混合检索、引用链路与 freshness。");
  assert.equal(session.refusalReason, null);
  assert.equal(session.providerName, "mock-answer");
  assert.equal(session.providerModel, "mock-model");
  assert.equal(repository.answerClaims.length, 1);
  assert.equal(repository.answerClaims[0]?.claimSlot, "claim_1");
  assert.equal(repository.answerClaims[0]?.claimText, "应优先投入混合检索、引用链路与 freshness。");
  assert.equal(repository.answerClaims[0]?.freshnessBadge, "ready");
  assert.equal(repository.answerCitations.length, 1);
  assert.equal(repository.answerCitations[0]?.chunkId, "chunk-1");
  assert.equal(repository.answerCitations[0]?.claimSlot, "claim_1");
  assert.equal(repository.retrievalRunHits[0]?.usedInAnswer, true);
  assert.equal(repository.retrievalRunHits[1]?.usedInAnswer, false);
  assert.equal(repository.retrievalRunHits[1]?.exclusionReason, "low_support");
});

test("answer orchestration propagates typed scope filters into retrieval", async () => {
  const session = createSession({
    scopeMode: "global",
    scopePayload: {
      filters: {
        tags: ["strategy", " strategy ", "", "roadmap"],
        source_types: ["pdf", "invalid", "link", "pdf"],
        date_from: "2026-01-01T00:00:00.000Z",
        date_to: "2026-02-01T00:00:00.000Z"
      }
    }
  });
  const repository = createRepository({
    session,
    eligibleDocumentCount: 1,
    lexicalCandidates: [createCandidate("chunk-1", 0.8, null)],
    semanticCandidates: [createCandidate("chunk-1", null, 0.9)],
    chunkRecords: [createChunkRecord("chunk-1", "这里有足够的引用证据。")]
  });

  const handlers = createAnswerOrchestrationHandlers({
    repository,
    getEmbeddingProvider: () => createEmbeddingProvider(),
    getAnswerProvider: () =>
      createAnswerProvider({
        decision: "answered",
        answer_summary: "这里有足够的引用证据。",
        supporting_chunk_ids: ["chunk-1"]
      }),
    logger: createLogger()
  });

  await handlers.answer_session({
    name: "answer_session",
    id: "queue-job-filters",
    data: {
      sessionId: session.id
    },
    attemptsMade: 0
  });

  const expectedFilters: ScopeFilterSet = {
    tags: ["strategy", "roadmap"],
    source_types: ["pdf", "link"],
    date_from: "2026-01-01T00:00:00.000Z",
    date_to: "2026-02-01T00:00:00.000Z"
  };

  assert.deepEqual(repository.lastCountEligibleCall, {
    documentIds: null,
    filters: expectedFilters
  });
  assert.deepEqual(repository.lastLexicalCall, {
    question: session.question,
    documentIds: null,
    filters: expectedFilters,
    limit: 12
  });
  assert.deepEqual(repository.lastSemanticCall, {
    documentIds: null,
    filters: expectedFilters,
    limit: 12
  });
});

test("answer orchestration refuses immediately when no eligible evidence exists", async () => {
  const session = createSession({
    scopeMode: "global",
    scopePayload: null
  });
  const repository = createRepository({
    session,
    eligibleDocumentCount: 0
  });

  const handlers = createAnswerOrchestrationHandlers({
    repository,
    getEmbeddingProvider: () => createEmbeddingProvider(),
    getAnswerProvider: () => createAnswerProvider({
      decision: "refused",
      refusal_reason: "no evidence"
    }),
    logger: createLogger()
  });

  const result = await handlers.answer_session({
    name: "answer_session",
    id: "queue-job-2",
    data: {
      sessionId: session.id
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(session.status, "refused");
  assert.equal(session.diagnosisCode, "retrieval_no_hits");
  assert.match(session.refusalReason ?? "", /没有检索到足够相关的可引用证据/);
});

test("answer orchestration marks session failed when retrieving transition throws", async () => {
  const session = createSession();
  const repository = createRepository({
    session,
    eligibleDocumentCount: 1,
    lexicalCandidates: [createCandidate("chunk-1", 0.8, null)],
    semanticCandidates: [createCandidate("chunk-1", null, 0.9)],
    chunkRecords: [createChunkRecord("chunk-1", "这里有足够的引用证据。")]
  });
  repository.markAnswerSessionRetrieving = async () => {
    throw new Error("retrieving transition failed");
  };

  const handlers = createAnswerOrchestrationHandlers({
    repository,
    getEmbeddingProvider: () => createEmbeddingProvider(),
    getAnswerProvider: () => createAnswerProvider({
      decision: "answered",
      answer_summary: "这里有足够的引用证据。",
      supporting_chunk_ids: ["chunk-1"]
    }),
    logger: createLogger()
  });

  const result = await handlers.answer_session({
    name: "answer_session",
    id: "queue-job-retrieving-failure",
    data: {
      sessionId: session.id
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "failed");
  assert.equal(session.status, "failed");
  assert.equal(session.refusalReason, "retrieving transition failed");
});

function createSession(overrides: Partial<AnswerSessionRecord> = {}): AnswerSessionRecord {
  return {
    id: "session-1",
    ownerId: null,
    queueJobId: null,
    continuedFromSessionId: null,
    question: "未来两个月最值得投入什么？",
    scopeMode: "global",
    scopePayload: null,
    retrievalMode: "hybrid",
    status: "idle",
    answerSummary: null,
    refusalReason: null,
    diagnosisCode: null,
    providerName: null,
    providerModel: null,
    latencyMs: null,
    promptTokens: null,
    completionTokens: null,
    totalCostUsd: null,
    createdAt: new Date("2026-04-08T00:00:00.000Z"),
    updatedAt: new Date("2026-04-08T00:00:00.000Z"),
    finishedAt: null,
    ...overrides
  };
}

function createCandidate(
  chunkId: string,
  lexicalScore: number | null,
  semanticScore: number | null
): RetrievalChunkCandidateRecord {
  return {
    id: chunkId,
    documentId: "doc-1",
    documentTitle: "路线图",
    chunkIndex: 0,
    sectionLabel: "summary",
    pageRef: "p1",
    contentText: "建议优先投入混合检索、引用链路与 freshness。",
    contentSha256: "sha",
    citationLocator: { page: 1 },
    lexicalScore,
    semanticScore,
    finalScore: lexicalScore ?? semanticScore
  };
}

function createChunkRecord(chunkId: string, contentText: string): AnswerChunkRecord {
  return {
    id: chunkId,
    documentId: "doc-1",
    documentTitle: "路线图",
    chunkIndex: Number(chunkId.split("-").at(-1) ?? 0) - 1,
    sectionLabel: "summary",
    pageRef: "p1",
    contentText,
    contentSha256: "sha",
    citationLocator: { page: 1 }
  };
}

function createEmbeddingProvider(): EmbeddingProvider {
  return {
    async embed() {
      return {
        providerName: "mock-embedding",
        model: "mock-model",
        vectors: [[0.1, 0.2, 0.3]],
        dimensions: 3,
        inputCount: 1,
        usage: null,
        attempts: 1,
        latencyMs: 1,
        raw: null
      };
    }
  };
}

function createAnswerProvider(response: {
  decision: "answered" | "needs_scope" | "refused";
  answer_summary?: string | null;
  refusal_reason?: string | null;
  scope_suggestions?: string[] | null;
  supporting_chunk_ids?: string[] | null;
  claims?: Array<{
    claim_text?: string | null;
    supporting_chunk_ids?: string[] | null;
  }> | null;
}): AnswerProvider {
  return {
    async generate() {
      return {
        providerName: "mock-answer",
        model: "mock-model",
        text: JSON.stringify(response),
        finishReason: "stop",
        usage: {
          promptTokens: 8,
          completionTokens: 4,
          totalTokens: 12
        },
        attempts: 1,
        latencyMs: 2,
        raw: response
      };
    }
  };
}

function createLogger() {
  return {
    debug() {},
    info() {},
    warn() {},
    error() {}
  };
}

function createRepository(config: {
  session: AnswerSessionRecord;
  eligibleDocumentCount: number;
  lexicalCandidates?: RetrievalChunkCandidateRecord[];
  semanticCandidates?: RetrievalChunkCandidateRecord[];
  chunkRecords?: AnswerChunkRecord[];
}) {
  const state = {
    session: config.session,
    retrievalRuns: [] as RetrievalRunRecord[],
    lastCountEligibleCall: null as {
      documentIds: string[] | null;
      filters: ScopeFilterSet | null;
    } | null,
    lastLexicalCall: null as {
      question: string;
      documentIds: string[] | null;
      filters: ScopeFilterSet | null;
      limit: number;
    } | null,
    lastSemanticCall: null as {
      documentIds: string[] | null;
      filters: ScopeFilterSet | null;
      limit: number;
    } | null,
    retrievalRunHits: [] as Array<{
      retrievalRunId: string;
      documentId: string;
      chunkId: string | null;
      rank: number;
      lexicalScore: number | null;
      semanticScore: number | null;
      finalScore: number | null;
      usedInAnswer: boolean;
      exclusionReason: string | null;
    }>,
    answerCitations: [] as Array<{
      sessionId: string;
      documentId: string;
      chunkId: string;
      claimSlot: string;
      quoteText: string;
      locator: Record<string, unknown> | null;
    }>,
    answerClaims: [] as Array<{
      sessionId: string;
      claimSlot: string;
      displayOrder: number;
      claimText: string;
      freshnessBadge: string;
    }>,
    chunkRecords: config.chunkRecords ?? []
  };

  const repository = {
    async getAnswerSessionById() {
      return state.session;
    },
    async markAnswerSessionRetrieving(sessionId: string, queueJobId: string | null) {
      state.session.queueJobId = queueJobId;
      state.session.status = "retrieving";
    },
    async markAnswerSessionSynthesizing() {
      state.session.status = "synthesizing";
    },
    async markAnswerSessionAnswered(
      _sessionId: string,
      values: {
        answerSummary: string;
        providerName: string | null;
        providerModel: string | null;
        latencyMs: number | null;
        promptTokens: number | null;
        completionTokens: number | null;
        totalCostUsd: string | number | null;
      }
    ) {
      state.session.status = "answered";
      state.session.answerSummary = values.answerSummary;
      state.session.providerName = values.providerName;
      state.session.providerModel = values.providerModel;
      state.session.latencyMs = values.latencyMs;
      state.session.promptTokens = values.promptTokens;
      state.session.completionTokens = values.completionTokens;
      state.session.totalCostUsd = values.totalCostUsd === null ? null : String(values.totalCostUsd);
      state.session.finishedAt = new Date();
    },
    async markAnswerSessionNeedsScope(
      _sessionId: string,
      values: {
        refusalReason: string;
        diagnosisCode: string | null;
        providerName: string | null;
        providerModel: string | null;
        latencyMs: number | null;
        promptTokens: number | null;
        completionTokens: number | null;
        totalCostUsd: string | number | null;
      }
    ) {
      state.session.status = "needs_scope";
      state.session.refusalReason = values.refusalReason;
      state.session.diagnosisCode = values.diagnosisCode;
      state.session.providerName = values.providerName;
      state.session.providerModel = values.providerModel;
      state.session.latencyMs = values.latencyMs;
      state.session.promptTokens = values.promptTokens;
      state.session.completionTokens = values.completionTokens;
      state.session.totalCostUsd = values.totalCostUsd === null ? null : String(values.totalCostUsd);
      state.session.finishedAt = new Date();
    },
    async markAnswerSessionRefused(
      _sessionId: string,
      values: {
        refusalReason: string;
        diagnosisCode: string | null;
        providerName: string | null;
        providerModel: string | null;
        latencyMs: number | null;
        promptTokens: number | null;
        completionTokens: number | null;
        totalCostUsd: string | number | null;
      }
    ) {
      state.session.status = "refused";
      state.session.refusalReason = values.refusalReason;
      state.session.diagnosisCode = values.diagnosisCode;
      state.session.providerName = values.providerName;
      state.session.providerModel = values.providerModel;
      state.session.latencyMs = values.latencyMs;
      state.session.promptTokens = values.promptTokens;
      state.session.completionTokens = values.completionTokens;
      state.session.totalCostUsd = values.totalCostUsd === null ? null : String(values.totalCostUsd);
      state.session.finishedAt = new Date();
    },
    async markAnswerSessionFailed(
      _sessionId: string,
      values: {
        refusalReason: string | null;
        diagnosisCode: string | null;
        providerName: string | null;
        providerModel: string | null;
        latencyMs: number | null;
        promptTokens: number | null;
        completionTokens: number | null;
        totalCostUsd: string | number | null;
      }
    ) {
      state.session.status = "failed";
      state.session.refusalReason = values.refusalReason;
      state.session.diagnosisCode = values.diagnosisCode;
      state.session.providerName = values.providerName;
      state.session.providerModel = values.providerModel;
      state.session.latencyMs = values.latencyMs;
      state.session.promptTokens = values.promptTokens;
      state.session.completionTokens = values.completionTokens;
      state.session.totalCostUsd = values.totalCostUsd === null ? null : String(values.totalCostUsd);
      state.session.finishedAt = new Date();
    },
    async countEligibleDocuments(documentIds: string[] | null, filters: ScopeFilterSet | null) {
      state.lastCountEligibleCall = {
        documentIds,
        filters
      };
      return config.eligibleDocumentCount;
    },
    async listLexicalChunkCandidates(
      question: string,
      documentIds: string[] | null,
      filters: ScopeFilterSet | null,
      limit: number
    ) {
      state.lastLexicalCall = {
        question,
        documentIds,
        filters,
        limit
      };
      return config.lexicalCandidates ?? [];
    },
    async listSemanticChunkCandidates(
      _queryVector: readonly number[],
      documentIds: string[] | null,
      filters: ScopeFilterSet | null,
      limit: number
    ) {
      state.lastSemanticCall = {
        documentIds,
        filters,
        limit
      };
      return config.semanticCandidates ?? [];
    },
    async createRetrievalRun(values: {
      sessionId: string;
      queryNormalized: string;
      eligibleDocumentCount: number;
      lexicalHitCount: number;
      semanticHitCount: number;
      mergedHitCount: number;
      rerankStrategy: string;
      latencyMs: number | null;
    }) {
      const run: RetrievalRunRecord = {
        id: "run-1",
        sessionId: values.sessionId,
        queryNormalized: values.queryNormalized,
        eligibleDocumentCount: values.eligibleDocumentCount,
        lexicalHitCount: values.lexicalHitCount,
        semanticHitCount: values.semanticHitCount,
        mergedHitCount: values.mergedHitCount,
        rerankStrategy: values.rerankStrategy,
        latencyMs: values.latencyMs,
        createdAt: new Date()
      };
      state.retrievalRuns.push(run);
      return run;
    },
    async insertRetrievalRunHits(values: Array<{
      retrievalRunId: string;
      documentId: string;
      chunkId: string | null;
      rank: number;
      lexicalScore: number | null;
      semanticScore: number | null;
      finalScore: number | null;
      usedInAnswer: boolean;
      exclusionReason: string | null;
    }>) {
      state.retrievalRunHits.push(...values);
    },
    async markRetrievalHitsUsedInAnswer(retrievalRunId: string, chunkIds: string[]) {
      for (const hit of state.retrievalRunHits) {
        if (hit.retrievalRunId === retrievalRunId && hit.chunkId && chunkIds.includes(hit.chunkId)) {
          hit.usedInAnswer = true;
          hit.exclusionReason = null;
        }
      }
    },
    async markRetrievalHitsExcludedFromAnswer(retrievalRunId: string, chunkIds: string[], exclusionReason: string) {
      for (const hit of state.retrievalRunHits) {
        if (
          hit.retrievalRunId === retrievalRunId &&
          hit.chunkId &&
          chunkIds.includes(hit.chunkId) &&
          !hit.usedInAnswer &&
          hit.exclusionReason === null
        ) {
          hit.exclusionReason = exclusionReason;
        }
      }
    },
    async insertAnswerClaims(values: Array<{
      sessionId: string;
      claimSlot: string;
      displayOrder: number;
      claimText: string;
      freshnessBadge: string;
    }>) {
      state.answerClaims.push(...values);
    },
    async insertAnswerCitations(values: Array<{
      sessionId: string;
      documentId: string;
      chunkId: string;
      claimSlot: string;
      quoteText: string;
      locator: Record<string, unknown> | null;
    }>) {
      state.answerCitations.push(...values);
    },
    async listChunksByIds(chunkIds: string[]) {
      return state.chunkRecords.filter((record) => chunkIds.includes(record.id));
    },
    async withTransaction<T>(callback: (db: unknown) => Promise<T>) {
      return callback({});
    }
  } as unknown as AnswerRepository & {
    lastCountEligibleCall: typeof state.lastCountEligibleCall;
    lastLexicalCall: typeof state.lastLexicalCall;
    lastSemanticCall: typeof state.lastSemanticCall;
    retrievalRunHits: typeof state.retrievalRunHits;
    answerClaims: typeof state.answerClaims;
    answerCitations: typeof state.answerCitations;
  };

  return Object.defineProperties(repository, {
    lastCountEligibleCall: {
      get: () => state.lastCountEligibleCall
    },
    lastLexicalCall: {
      get: () => state.lastLexicalCall
    },
    lastSemanticCall: {
      get: () => state.lastSemanticCall
    },
    retrievalRunHits: {
      get: () => state.retrievalRunHits
    },
    answerClaims: {
      get: () => state.answerClaims
    },
    answerCitations: {
      get: () => state.answerCitations
    }
  });
}
