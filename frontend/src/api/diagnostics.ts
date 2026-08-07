import { api } from "./client";
import type { Page } from "./types";

/** 后端 `AiDiagnosticsOverview`。四个可空计数表示"读不到"，不是 0。 */
export interface DiagnosticsOverview {
  generatedAt: string;
  policyVersion: string;
  configFingerprint: string;
  retention: {
    maxAgeDays: number;
    maxLogicalCalls: number;
    oldestAvailableAt: string | null;
  };
  summary: {
    eligibleCandidates: number;
    openBreakers: number;
    failovers24h: number | null;
    failedOrInterrupted24h: number | null;
    diagnosticsDroppedSinceStart: number | null;
    diagnosticWriteFailuresSinceStart: number | null;
  };
  deployments: DeploymentView[];
  breakers: BreakerView[];
}

export interface DeploymentView {
  providerId: string;
  providerDisplayName: string | null;
  deploymentId: string;
  modelIdentity: string;
  capabilities: Capability[];
  tiers: string[];
  dataRegion: string | null;
  enabled: boolean;
  configurationStatus: string | null;
  verifiedCapabilities: Capability[];
}

export interface BreakerView {
  breakerKey: string;
  providerId: string;
  deploymentId: string;
  modelIdentity: string;
  capability: Capability;
  state: BreakerState;
  allowsCall: boolean;
  halfOpenPermissionInUse: boolean;
  lastChangedAt: string | null;
  lastChangeReason: string | null;
  recent: { success: number; failure: number; rateLimited: number; slow: number };
}

export type Capability = "CHAT" | "STREAM_CHAT" | "EMBEDDING" | "RERANK";
export type BreakerState = "CLOSED" | "OPEN" | "HALF_OPEN" | "DISABLED";
export type CallOutcome = "SUCCEEDED" | "DEGRADED" | "FAILED" | "CANCELED" | "STREAM_FAILED";

export interface Target {
  providerId: string;
  deploymentId: string;
  modelIdentity: string;
}

export interface DiagnosticCall {
  id: string;
  requestId: string;
  capability: Capability;
  serviceTier: string | null;
  outcome: CallOutcome;
  actualProviderId: string | null;
  actualDeploymentId: string | null;
  actualModelIdentity: string | null;
  lastAttemptTarget: Target | null;
  attemptCount: number;
  retryCount: number;
  failoverCount: number;
  failoverOccurred: boolean;
  mixedExecution: boolean;
  contributingDeploymentCount: number;
  committed: boolean;
  totalDurationMs: number;
  firstMeaningfulChunkMs: number | null;
  startedAt: string;
  normalizedErrorType: string | null;
}

export interface DiagnosticCallDetail {
  call: DiagnosticCall & {
    requiredModelIdentity: string | null;
    policyVersion: string;
    configFingerprint: string;
    providerRequestIdMasked: string | null;
    failurePhase: string | null;
    terminationReason: string;
    httpStatus: number | null;
    routeDurationMs: number | null;
    firstUpstreamEventMs: number | null;
    firstMeaningfulChunkMs: number | null;
    downstreamCommitMs: number | null;
    completedAt: string;
  };
  candidates: DetailCandidate[];
  routeEvents: DetailRouteEvent[];
  attempts: DetailAttempt[];
  capabilityDetails: CapabilityDetails;
}

export interface DetailCandidate {
  candidateOrdinal: number;
  providerId: string;
  deploymentId: string;
  modelIdentity: string;
  capability: Capability;
  serviceTier: string | null;
  decision: "ELIGIBLE" | "FILTERED";
  plannedOrder: number | null;
  rejectionReason: string | null;
  defaultCandidate: boolean;
  priority: number;
  breakerStateSnapshot: BreakerState | null;
}

export interface DetailRouteEvent {
  sequenceNo: number;
  occurredOffsetMs: number;
  eventType: string;
  workUnitIndex: number | null;
  attemptNo: number | null;
  candidateOrder: number | null;
  providerId: string | null;
  deploymentId: string | null;
  reason: string | null;
  waitDurationMs: number | null;
  remainingBudgetMs: number | null;
  eligibleCandidateCount: number | null;
}

export interface DetailAttempt {
  attemptNo: number;
  workUnitIndex: number;
  inputFromInclusive: number | null;
  inputToExclusive: number | null;
  candidateOrder: number;
  attemptKind: "INITIAL" | "RETRY" | "FAILOVER";
  startedSequenceNo: number;
  completedSequenceNo: number;
  providerId: string;
  deploymentId: string;
  modelIdentity: string;
  breakerStateBefore: BreakerState;
  breakerStateAfter: BreakerState | null;
  outcome: string;
  normalizedErrorType: string | null;
  failurePhase: string | null;
  httpStatus: number | null;
  providerRequestIdMasked: string | null;
  startedOffsetMs: number;
  firstUpstreamEventMs: number | null;
  firstMeaningfulChunkMs: number | null;
  downstreamCommitMs: number | null;
  durationMs: number;
  inputTokenCount: number | null;
  outputTokenCount: number | null;
  streamEventCount: number | null;
}

export interface CapabilityDetails {
  chat?: { finishReason: string | null; inputTokenCount: number | null; outputTokenCount: number | null };
  stream?: {
    firstUpstreamEventMs: number | null;
    firstMeaningfulChunkMs: number | null;
    downstreamCommitMs: number | null;
    streamEventCount: number | null;
    incomplete: boolean;
  };
  embedding?: {
    modelIdentity: string | null;
    inputCount: number | null;
    batchCount: number | null;
    dimensions: number | null;
    mixedExecution: boolean;
    contributingDeployments: Target[];
    batches: {
      workUnitIndex: number;
      inputFromInclusive: number | null;
      inputToExclusive: number | null;
      actualExecution: Target | null;
      attemptCount: number;
      retryCount: number;
      failoverCount: number;
    }[];
    validation: { countAndOrderValid: boolean; dimensionsValid: boolean; finiteValues: boolean } | null;
  };
  rerank?: {
    documentCount: number | null;
    topN: number | null;
    rerankApplied: boolean | null;
    degradationReason: string | null;
  };
}

/** 后端 `/health/ready` 的逐项探测结果。 */
export interface Readiness {
  status: string;
  checks: Record<string, string>;
}

export interface CallFilters {
  window: "1h" | "24h" | "7d";
  capability?: string;
  tier?: string;
  outcomes: CallOutcome[];
  providerId?: string;
  requestId?: string;
  failoverOccurred?: boolean;
  page: number;
}

const WINDOW_HOURS: Record<CallFilters["window"], number> = { "1h": 1, "24h": 24, "7d": 24 * 7 };

export function fetchOverview(signal?: AbortSignal): Promise<DiagnosticsOverview> {
  return api.get<DiagnosticsOverview>("/ai/diagnostics/overview", { signal });
}

export function fetchReadiness(signal?: AbortSignal): Promise<Readiness> {
  return api.get<Readiness>("/health/ready", { signal });
}

export function fetchCallDetail(id: string, signal?: AbortSignal): Promise<DiagnosticCallDetail> {
  return api.get<DiagnosticCallDetail>(`/ai/diagnostics/calls/${id}`, { signal });
}

/**
 * `outcome` 是可重复参数（同字段 OR），{@link api.get} 的 query 是扁平 map，装不下，
 * 因此这里自己拼查询串。摘要下钻必须走这条真实请求，不能拿当前已加载的一页近似过滤。
 */
export function fetchCalls(
  filters: CallFilters,
  signal?: AbortSignal,
): Promise<Page<DiagnosticCall>> {
  const params = new URLSearchParams();
  const now = Date.now();
  params.set("from", new Date(now - WINDOW_HOURS[filters.window] * 3600_000).toISOString());
  params.set("to", new Date(now).toISOString());
  params.set("page", String(filters.page));
  params.set("size", "20");
  if (filters.capability) params.set("capability", filters.capability);
  if (filters.tier) params.set("tier", filters.tier);
  if (filters.providerId) params.set("providerId", filters.providerId);
  if (filters.requestId) params.set("requestId", filters.requestId);
  if (filters.failoverOccurred !== undefined) {
    params.set("failoverOccurred", String(filters.failoverOccurred));
  }
  for (const outcome of filters.outcomes) {
    params.append("outcome", outcome);
  }
  return api.get<Page<DiagnosticCall>>(`/ai/diagnostics/calls?${params.toString()}`, { signal });
}
