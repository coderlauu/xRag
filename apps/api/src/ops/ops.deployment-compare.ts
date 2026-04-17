import type {
  DeploymentSmokeStatus,
  OpsDeploymentCompareResponse,
  OpsDiagnosticSample,
  OpsDiagnosticSampleKind,
  OpsTrendWindow
} from "@xrag/shared-types";
import type { DatabaseClient } from "../database/database.service";
import { classifyDeploymentSamples, listRiskSamplesForWindow } from "./ops.diagnostic-samples";

export type DeploymentCompareRecord = {
  id: string;
  environment: string;
  commitSha: string | null;
  workflowRunId: string | null;
  currentImageTag: string;
  previousStableImageTag: string | null;
  smokeStatus: DeploymentSmokeStatus;
  smokeAt: Date | null;
  deployedAt: Date;
  evidenceUrl: string | null;
};

export type DeploymentCompareBaselineRecord = {
  id: string;
  currentImageTag: string;
} | null;

export async function getDeploymentCompareSamples(input: {
  db: DatabaseClient;
  deployment: DeploymentCompareRecord;
  window: OpsTrendWindow;
  sampleKind?: OpsDiagnosticSampleKind;
}) {
  const { beforeStart, beforeEnd, afterStart, afterEnd } = getDeploymentWindows(
    input.deployment.deployedAt,
    input.window
  );

  const [beforeCandidates, afterCandidates] = await Promise.all([
    listRiskSamplesForWindow(input.db, {
      startAt: beforeStart,
      endAt: beforeEnd,
      origin: "release_compare",
      sampleKind: input.sampleKind,
      relatedDeploymentRecordId: input.deployment.id
    }),
    listRiskSamplesForWindow(input.db, {
      startAt: afterStart,
      endAt: afterEnd,
      origin: "release_compare",
      sampleKind: input.sampleKind,
      relatedDeploymentRecordId: input.deployment.id
    })
  ]);

  return {
    beforeSamples: beforeCandidates.map((candidate) => candidate.sample),
    affectedSamples: classifyDeploymentSamples({
      beforeSamples: beforeCandidates,
      afterSamples: afterCandidates
    })
  };
}

export function buildDeploymentCompareResponse(input: {
  deployment: DeploymentCompareRecord;
  previousDeployment: DeploymentCompareBaselineRecord;
  relatedEvaluationRunRef: string | null;
  window: OpsTrendWindow;
  beforeSamples?: OpsDiagnosticSample[];
  affectedSamples?: OpsDiagnosticSample[];
  generatedAt?: Date;
}): OpsDeploymentCompareResponse {
  const generatedAt = input.generatedAt ?? new Date();
  const { beforeStart, beforeEnd, afterStart, afterEnd } = getDeploymentWindows(input.deployment.deployedAt, input.window);
  const beforeSamples = input.beforeSamples ?? [];
  const affectedSamples = input.affectedSamples ?? [];

  return {
    generated_at: generatedAt.toISOString(),
    deployment: {
      deployment_record_id: input.deployment.id,
      environment: input.deployment.environment,
      commit_sha: input.deployment.commitSha,
      workflow_run_id: input.deployment.workflowRunId,
      current_image_tag: input.deployment.currentImageTag,
      previous_stable_image_tag: input.deployment.previousStableImageTag,
      smoke_status: input.deployment.smokeStatus,
      smoke_at: input.deployment.smokeAt?.toISOString() ?? null,
      deployed_at: input.deployment.deployedAt.toISOString(),
      evidence_url: input.deployment.evidenceUrl
    },
    baseline: {
      previous_stable_image_tag: input.deployment.previousStableImageTag,
      previous_deployment_record_id: input.previousDeployment?.id ?? null,
      related_evaluation_run_ref: input.relatedEvaluationRunRef
    },
    before_window: {
      start_at: beforeStart.toISOString(),
      end_at: beforeEnd.toISOString(),
      sample_count: beforeSamples.length,
      high_severity_count: beforeSamples.filter((sample) => sample.severity === "high").length
    },
    after_window: {
      start_at: afterStart.toISOString(),
      end_at: afterEnd.toISOString(),
      sample_count: affectedSamples.length,
      high_severity_count: affectedSamples.filter((sample) => sample.severity === "high").length
    },
    delta_summary: {
      regression_count: affectedSamples.filter((sample) => sample.regression_class !== null).length,
      new_regression_count: affectedSamples.filter((sample) => sample.regression_class === "new_regression").length,
      existing_debt_count: affectedSamples.filter((sample) => sample.regression_class === "existing_debt").length,
      affected_answer_session_count: affectedSamples.filter((sample) => sample.sample_kind === "answer_session").length,
      affected_document_count: affectedSamples.filter((sample) => sample.sample_kind === "document_pipeline").length,
      summary: affectedSamples.length === 0
        ? "No affected samples were identified in the selected deployment window."
        : `${affectedSamples.length} affected samples were identified in the selected deployment window; ${
            affectedSamples.filter((sample) => sample.regression_class === "new_regression").length
          } are classified as new regressions.`
    },
    affected_samples: affectedSamples
  };
}

export function getDeploymentWindows(deployedAt: Date, window: OpsTrendWindow) {
  const durationMs = getWindowDurationMs(window);
  const beforeStart = new Date(deployedAt.getTime() - durationMs);
  const beforeEnd = new Date(deployedAt);
  const afterStart = new Date(deployedAt);
  const afterEnd = new Date(deployedAt.getTime() + durationMs);

  return {
    beforeStart,
    beforeEnd,
    afterStart,
    afterEnd
  };
}

function getWindowDurationMs(window: OpsTrendWindow) {
  switch (window) {
    case "24h":
      return 24 * 60 * 60 * 1000;
    case "30d":
      return 30 * 24 * 60 * 60 * 1000;
    case "7d":
    default:
      return 7 * 24 * 60 * 60 * 1000;
  }
}
