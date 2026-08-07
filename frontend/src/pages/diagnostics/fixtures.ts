import type {
  DiagnosticCall,
  DiagnosticCallDetail,
  DiagnosticsOverview,
} from "../../api/diagnostics";

/**
 * Trace Console 测试夹具。
 *
 * 这里的对象**故意不声明为对应的 interface 类型**：部分测试需要往响应里塞进 API 契约之外的
 * 字段（见 `overviewWithLeakedFields`），用来证明页面只渲染白名单字段。写死类型会让那些
 * 多余字段过不了编译，反而验不了真正想验的东西。
 */

export const overview: DiagnosticsOverview = {
  generatedAt: "2026-08-07T10:00:00Z",
  policyVersion: "phase2-v1",
  configFingerprint: "fp-abc123",
  retention: {
    maxAgeDays: 7,
    maxLogicalCalls: 10000,
    oldestAvailableAt: "2026-08-01T10:00:00Z",
  },
  summary: {
    eligibleCandidates: 3,
    openBreakers: 1,
    failovers24h: 4,
    failedOrInterrupted24h: 2,
    diagnosticsDroppedSinceStart: 0,
    diagnosticWriteFailuresSinceStart: 0,
  },
  deployments: [
    {
      providerId: "aliyun-model-studio",
      providerDisplayName: "阿里云百炼",
      deploymentId: "aliyun-qwen-plus-cn",
      modelIdentity: "qwen-plus-2025-12-01",
      capabilities: ["CHAT", "STREAM_CHAT"],
      tiers: ["BALANCED"],
      dataRegion: "CN_BEIJING",
      enabled: true,
      configurationStatus: "CONFIGURED",
      verifiedCapabilities: [],
    },
    {
      providerId: "aliyun-model-studio",
      providerDisplayName: "阿里云百炼",
      deploymentId: "aliyun-qwen3-rerank-cn",
      modelIdentity: "qwen3-rerank",
      capabilities: ["RERANK"],
      tiers: ["BALANCED"],
      dataRegion: "CN_BEIJING",
      enabled: false,
      configurationStatus: "MISSING_SECRET",
      verifiedCapabilities: [],
    },
  ],
  breakers: [
    {
      breakerKey: "aliyun-model-studio/aliyun-qwen-plus-cn/CHAT",
      providerId: "aliyun-model-studio",
      deploymentId: "aliyun-qwen-plus-cn",
      modelIdentity: "qwen-plus-2025-12-01",
      capability: "CHAT",
      state: "OPEN",
      allowsCall: false,
      halfOpenPermissionInUse: false,
      lastChangedAt: "2026-08-07T09:55:00Z",
      lastChangeReason: "FAILURE_RATE_EXCEEDED",
      recent: { success: 2, failure: 8, rateLimited: 0, slow: 1 },
    },
  ],
};

export const call: DiagnosticCall = {
  id: "call-1",
  requestId: "req_internal_0001",
  capability: "STREAM_CHAT",
  serviceTier: "BALANCED",
  outcome: "STREAM_FAILED",
  actualProviderId: "aliyun-model-studio",
  actualDeploymentId: "aliyun-qwen-plus-cn",
  actualModelIdentity: "qwen-plus-2025-12-01",
  lastAttemptTarget: null,
  attemptCount: 2,
  retryCount: 0,
  failoverCount: 1,
  failoverOccurred: true,
  mixedExecution: false,
  contributingDeploymentCount: 1,
  committed: true,
  totalDurationMs: 4210,
  firstMeaningfulChunkMs: 380,
  startedAt: "2026-08-07T09:59:00Z",
  normalizedErrorType: "UPSTREAM_STREAM_BROKEN",
};

export const callsPage = { items: [call], page: 1, size: 20, total: 1 };

export const emptyCallsPage = { items: [], page: 1, size: 20, total: 0 };

export const callDetail: DiagnosticCallDetail = {
  call: {
    ...call,
    requiredModelIdentity: null,
    policyVersion: "phase2-v1",
    configFingerprint: "fp-abc123",
    providerRequestIdMasked: "req_****1b7c",
    failurePhase: "STREAMING",
    terminationReason: "UPSTREAM_STREAM_BROKEN",
    httpStatus: 200,
    routeDurationMs: 3,
    firstUpstreamEventMs: 210,
    firstMeaningfulChunkMs: 380,
    downstreamCommitMs: 395,
    completedAt: "2026-08-07T09:59:04Z",
  },
  candidates: [
    {
      candidateOrdinal: 1,
      providerId: "aliyun-model-studio",
      deploymentId: "aliyun-qwen-plus-cn",
      modelIdentity: "qwen-plus-2025-12-01",
      capability: "STREAM_CHAT",
      serviceTier: "BALANCED",
      decision: "ELIGIBLE",
      plannedOrder: 1,
      rejectionReason: null,
      defaultCandidate: true,
      priority: 10,
      breakerStateSnapshot: "CLOSED",
    },
  ],
  routeEvents: [
    {
      sequenceNo: 1,
      occurredOffsetMs: 0,
      eventType: "ROUTE_PLANNED",
      workUnitIndex: null,
      attemptNo: null,
      candidateOrder: null,
      providerId: null,
      deploymentId: null,
      reason: null,
      waitDurationMs: null,
      remainingBudgetMs: 60000,
      eligibleCandidateCount: 2,
    },
  ],
  attempts: [
    {
      attemptNo: 1,
      workUnitIndex: 0,
      inputFromInclusive: null,
      inputToExclusive: null,
      candidateOrder: 1,
      attemptKind: "INITIAL",
      startedSequenceNo: 2,
      completedSequenceNo: 3,
      providerId: "aliyun-model-studio",
      deploymentId: "aliyun-qwen-plus-cn",
      modelIdentity: "qwen-plus-2025-12-01",
      breakerStateBefore: "CLOSED",
      breakerStateAfter: "CLOSED",
      outcome: "FAILED",
      normalizedErrorType: "UPSTREAM_STREAM_BROKEN",
      failurePhase: "STREAMING",
      httpStatus: 200,
      providerRequestIdMasked: "req_****1b7c",
      startedOffsetMs: 5,
      firstUpstreamEventMs: 210,
      firstMeaningfulChunkMs: 380,
      downstreamCommitMs: 395,
      durationMs: 4200,
      inputTokenCount: 120,
      outputTokenCount: 44,
      streamEventCount: 17,
    },
  ],
  capabilityDetails: {
    stream: {
      firstUpstreamEventMs: 210,
      firstMeaningfulChunkMs: 380,
      downstreamCommitMs: 395,
      streamEventCount: 17,
      incomplete: true,
    },
  },
};

export const readiness = {
  status: "ready",
  checks: { postgres: "ok", redis: "ok", s3: "ok" },
};

/**
 * UI-08 用：在契约字段之外塞进本不该出现在页面上的东西。
 *
 * 这不是模拟当前后端——当前后端不返回这些字段。它模拟的是**未来某次后端改动顺手多带了字段**，
 * 用来证明前端是按白名单逐字段渲染的，而不是遍历响应对象。前者多一个字段什么也不会发生，
 * 后者会把 Prompt 正文直接印到诊断页上。
 */
export const overviewWithLeakedFields = {
  ...overview,
  apiKey: "sk-live-must-never-render",
  authorizationHeader: "Bearer must-never-render",
  deployments: overview.deployments.map((deployment) => ({
    ...deployment,
    secretValue: "sk-deployment-must-never-render",
  })),
};

export const callDetailWithLeakedFields = {
  ...callDetail,
  call: {
    ...callDetail.call,
    promptText: "用户的原始问题正文，必须永不出现在诊断页",
    responseText: "模型输出正文，必须永不出现在诊断页",
    rawErrorBody: '{"error":{"message":"upstream said something raw"}}',
    providerRequestIdRaw: "req-7f3a9c22e1d84b6690ab34ff2c5e1b7c",
  },
};

/** UI-10 用：两个进程内计数都大于 0。 */
export const overviewWithMissingDiagnostics: DiagnosticsOverview = {
  ...overview,
  summary: {
    ...overview.summary,
    diagnosticsDroppedSinceStart: 12,
    diagnosticWriteFailuresSinceStart: 3,
  },
};
