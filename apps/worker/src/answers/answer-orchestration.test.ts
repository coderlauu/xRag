import assert from "node:assert/strict";
import test from "node:test";
import type { AnswerProvider, EmbeddingProvider } from "../providers";
import type { AnswerRepository, AnswerSessionRecord, AnswerChunkRecord, RetrievalChunkCandidateRecord, RetrievalRunRecord } from "../database/answer-repository";
import { createAnswerOrchestrationHandlers } from "./answer-orchestration";

test("answer orchestration persists grounded answer citations", async () => {
  const session = createSession();
  const repository = createRepository({
    session,
    eligibleDocumentCount: 1,
    lexicalCandidates: [createCandidate("chunk-1", 0.8, null)],
    semanticCandidates: [createCandidate("chunk-1", null, 0.9)],
    chunkRecords: [
      {
        id: "chunk-1",
        documentId: "doc-1",
        documentTitle: "路线图",
        chunkIndex: 0,
        sectionLabel: "summary",
        pageRef: "p1",
        contentText: "建议优先投入混合检索、引用链路与 freshness。",
        contentSha256: "sha",
        citationLocator: { page: 1 }
      }
    ]
  });
  const embeddingProvider = createEmbeddingProvider();
  const answerProvider = createAnswerProvider({
    decision: "answered",
    answer_summary: "应优先投入混合检索、引用链路与 freshness。",
    supporting_chunk_ids: ["chunk-1"]
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
  assert.equal(repository.answerCitations.length, 1);
  assert.equal(repository.answerCitations[0]?.chunkId, "chunk-1");
  assert.equal(repository.retrievalRunHits[0]?.usedInAnswer, true);
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
    chunkRecords: config.chunkRecords ?? []
  };

  return Object.assign(
    {
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
    async countEligibleDocuments() {
      return config.eligibleDocumentCount;
    },
    async listLexicalChunkCandidates() {
      return config.lexicalCandidates ?? [];
    },
    async listSemanticChunkCandidates() {
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
    },
    {
      retrievalRunHits: state.retrievalRunHits,
      answerCitations: state.answerCitations
    }
  ) as unknown as AnswerRepository & {
    retrievalRunHits: typeof state.retrievalRunHits;
    answerCitations: typeof state.answerCitations;
  };
}
