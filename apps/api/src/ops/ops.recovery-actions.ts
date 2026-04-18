import type {
  OpsRecoveryActionAuditResponse,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionResponse,
  OpsRecoveryCandidateSourceType,
  OpsRecoveryFactSnapshot,
  OpsRecoveryStatusTimelineEntry,
  OpsRecoveryTargetType,
  OpsRecoveryActionType,
  OpsReplayRef
} from "@xrag/shared-types";
import { operatorRecoveryActions } from "../database/schema";

export type RecoveryActionRow = typeof operatorRecoveryActions.$inferSelect;

type ParsedRecoveryCandidateId = {
  sourceType: OpsRecoveryCandidateSourceType;
  actionType: OpsRecoveryActionType;
  targetType: OpsRecoveryTargetType;
  targetId: string;
};

const RECOVERY_SOURCE_TYPES: OpsRecoveryCandidateSourceType[] = [
  "diagnostic_sample",
  "answer_session_replay",
  "document_replay",
  "deployment_compare"
];

const RECOVERY_ACTION_TYPES: OpsRecoveryActionType[] = [
  "document_reindex",
  "document_retry",
  "answer_diagnostic_rerun"
];

const RECOVERY_TARGET_TYPES: OpsRecoveryTargetType[] = ["document", "answer_session"];

export function parseRecoveryCandidateId(candidateId: string): ParsedRecoveryCandidateId {
  const [sourceType, actionType, targetType, ...targetIdParts] = candidateId.split(":");
  const targetId = targetIdParts.join(":");

  if (
    !sourceType ||
    !actionType ||
    !targetType ||
    !targetId ||
    !RECOVERY_SOURCE_TYPES.includes(sourceType as OpsRecoveryCandidateSourceType) ||
    !RECOVERY_ACTION_TYPES.includes(actionType as OpsRecoveryActionType) ||
    !RECOVERY_TARGET_TYPES.includes(targetType as OpsRecoveryTargetType)
  ) {
    throw new Error("Invalid recovery candidate_id");
  }

  return {
    sourceType: sourceType as OpsRecoveryCandidateSourceType,
    actionType: actionType as OpsRecoveryActionType,
    targetType: targetType as OpsRecoveryTargetType,
    targetId
  };
}

export function buildRecoveryJobRef(jobId: string): OpsReplayRef {
  return {
    method: "GET",
    path: `/api/v1/jobs/${jobId}`
  };
}

export function readRecoveryPreview(row: RecoveryActionRow): OpsRecoveryActionPreviewResponse {
  return row.preview as unknown as OpsRecoveryActionPreviewResponse;
}

export function readRecoveryFactSnapshot(value: Record<string, unknown> | null): OpsRecoveryFactSnapshot | null {
  return value as unknown as OpsRecoveryFactSnapshot | null;
}

export function toRecoveryActionResponse(row: RecoveryActionRow): OpsRecoveryActionResponse {
  return {
    action_id: row.id,
    candidate_id: row.candidateId,
    status: row.status,
    action_type: row.actionType,
    target_type: row.targetType,
    target_refs: row.targetRefs as unknown as OpsRecoveryActionResponse["target_refs"],
    queue_job_refs: (row.queueJobRefs ?? []) as unknown as OpsReplayRef[],
    diagnosis_code: row.diagnosisCode as OpsRecoveryActionResponse["diagnosis_code"],
    error_message: row.errorMessage,
    created_at: row.createdAt.toISOString(),
    started_at: row.startedAt?.toISOString() ?? null,
    completed_at: row.completedAt?.toISOString() ?? null,
    updated_at: row.updatedAt.toISOString()
  };
}

export function buildRecoveryStatusTimeline(row: RecoveryActionRow): OpsRecoveryStatusTimelineEntry[] {
  const timeline: OpsRecoveryStatusTimelineEntry[] = [];

  if (row.status === "blocked" && !row.startedAt) {
    timeline.push({
      status: "blocked",
      at: (row.completedAt ?? row.updatedAt).toISOString(),
      summary: "Recovery action was blocked before execution."
    });
    return timeline;
  }

  timeline.push({
    status: "queued",
    at: row.createdAt.toISOString(),
    summary: "Operator-approved recovery action was created."
  });

  if (row.startedAt) {
    timeline.push({
      status: "running",
      at: row.startedAt.toISOString(),
      summary: "Downstream recovery work started."
    });
  }

  if (row.completedAt && row.status !== "queued" && row.status !== "running") {
    timeline.push({
      status: row.status,
      at: row.completedAt.toISOString(),
      summary: getTerminalSummary(row)
    });
  }

  return timeline;
}

export function buildRecoveryManualFollowUp(row: RecoveryActionRow): string[] {
  const firstTargetRef = row.targetRefs[0];
  const targetId = typeof firstTargetRef?.id === "string" ? firstTargetRef.id : null;

  if (row.actionType === "answer_diagnostic_rerun" && targetId) {
    return [
      `Review the existing replay at /api/v1/ops/replays/answer-sessions/${targetId}.`,
      "Keep answer reruns read-only in Phase 3B unless answer-quality contracts are re-frozen."
    ];
  }

  if (!targetId) {
    return ["Review the stored recovery facts and related jobs before taking more action."];
  }

  if (row.status === "succeeded") {
    return [`Verify the recovered document state at /api/v1/ops/replays/documents/${targetId}.`];
  }

  if (row.status === "blocked") {
    return [
      `Inspect the current document replay at /api/v1/ops/replays/documents/${targetId}.`,
      "Refresh the preview after the blocking preconditions are cleared."
    ];
  }

  if (row.status === "failed") {
    return [
      `Inspect the failed document replay at /api/v1/ops/replays/documents/${targetId}.`,
      "Review the related job refs and underlying diagnosis before retrying manually."
    ];
  }

  return [
    `Poll the action until terminal, then inspect /api/v1/ops/replays/documents/${targetId}.`
  ];
}

export function toRecoveryActionAuditResponse(
  row: RecoveryActionRow,
  generatedAt: Date
): OpsRecoveryActionAuditResponse {
  return {
    generated_at: generatedAt.toISOString(),
    action: toRecoveryActionResponse(row),
    actor: row.actor,
    reason: row.reason,
    source_facts: row.sourceFacts as unknown as OpsRecoveryFactSnapshot,
    preview: readRecoveryPreview(row),
    before_facts: row.beforeFacts as unknown as OpsRecoveryFactSnapshot,
    after_facts: readRecoveryFactSnapshot(row.afterFacts),
    status_timeline: buildRecoveryStatusTimeline(row),
    manual_follow_up: buildRecoveryManualFollowUp(row)
  };
}

function getTerminalSummary(row: RecoveryActionRow) {
  switch (row.status) {
    case "succeeded":
      return "Recovery action reached a successful terminal state.";
    case "failed":
      return row.errorMessage?.trim() || "Recovery action reached a failed terminal state.";
    case "cancelled":
      return "Recovery action was cancelled.";
    case "blocked":
      return row.errorMessage?.trim() || "Recovery action was blocked.";
    default:
      return "Recovery action updated.";
  }
}
