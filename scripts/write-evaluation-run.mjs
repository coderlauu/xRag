#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { openOpsFactDatabase } from "./lib/ops-fact-db.mjs";

function optionalString(value) {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function optionalNumber(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }

  const numeric = Number(value);
  if (Number.isNaN(numeric)) {
    throw new Error(`Invalid numeric value: ${value}`);
  }

  return numeric;
}

async function loadPayload() {
  const payloadPath = process.env.EVALUATION_RUN_JSON || process.argv[2];

  if (payloadPath) {
    const raw = await readFile(payloadPath, "utf8");
    return JSON.parse(raw);
  }

  return {
    run_ref: process.env.XRAG_EVAL_RUN_REF,
    environment: process.env.XRAG_EVAL_ENVIRONMENT,
    source: process.env.XRAG_EVAL_SOURCE,
    status: process.env.XRAG_EVAL_STATUS,
    commit_sha: process.env.XRAG_EVAL_COMMIT_SHA,
    dataset_version: process.env.XRAG_EVAL_DATASET_VERSION,
    recall_at_10: process.env.XRAG_EVAL_RECALL_AT_10,
    mrr: process.env.XRAG_EVAL_MRR,
    hit_in_answer_rate: process.env.XRAG_EVAL_HIT_IN_ANSWER_RATE,
    groundedness: process.env.XRAG_EVAL_GROUNDEDNESS,
    citation_coverage: process.env.XRAG_EVAL_CITATION_COVERAGE,
    refusal_precision: process.env.XRAG_EVAL_REFUSAL_PRECISION,
    latency_p95_ms: process.env.XRAG_EVAL_LATENCY_P95_MS,
    avg_token_cost_usd: process.env.XRAG_EVAL_AVG_TOKEN_COST_USD,
    embedding_backlog: process.env.XRAG_EVAL_EMBEDDING_BACKLOG,
    freshness_lag_p95_ms: process.env.XRAG_EVAL_FRESHNESS_LAG_P95_MS,
    artifact_url: process.env.XRAG_EVAL_ARTIFACT_URL,
    error_message: process.env.XRAG_EVAL_ERROR_MESSAGE,
    created_at: process.env.XRAG_EVAL_CREATED_AT,
    completed_at: process.env.XRAG_EVAL_COMPLETED_AT
  };
}

function toRecord(payload) {
  const runRef = optionalString(payload.run_ref);
  const environment = optionalString(payload.environment);
  const source = optionalString(payload.source);
  const status = optionalString(payload.status) || "completed";

  if (!runRef || !environment || !source) {
    throw new Error("Evaluation run payload requires run_ref, environment, and source.");
  }

  if (!["running", "completed", "failed"].includes(status)) {
    throw new Error(`Unsupported evaluation status: ${status}`);
  }

  const now = new Date().toISOString();
  const completedAt = status === "running" ? null : (optionalString(payload.completed_at) || now);

  return {
    id: randomUUID(),
    runRef,
    environment,
    source,
    status,
    commitSha: optionalString(payload.commit_sha),
    datasetVersion: optionalString(payload.dataset_version),
    recallAt10: optionalNumber(payload.recall_at_10),
    mrr: optionalNumber(payload.mrr),
    hitInAnswerRate: optionalNumber(payload.hit_in_answer_rate),
    groundedness: optionalNumber(payload.groundedness),
    citationCoverage: optionalNumber(payload.citation_coverage),
    refusalPrecision: optionalNumber(payload.refusal_precision),
    latencyP95Ms: optionalNumber(payload.latency_p95_ms),
    avgTokenCostUsd: optionalNumber(payload.avg_token_cost_usd),
    embeddingBacklog: optionalNumber(payload.embedding_backlog),
    freshnessLagP95Ms: optionalNumber(payload.freshness_lag_p95_ms),
    artifactUrl: optionalString(payload.artifact_url),
    errorMessage: optionalString(payload.error_message),
    createdAt: optionalString(payload.created_at) || now,
    completedAt
  };
}

async function main() {
  const payload = await loadPayload();
  const record = toRecord(payload);
  const { client, cleanup } = await openOpsFactDatabase();

  try {
    await client.query(
      `
        insert into evaluation_runs (
          id,
          run_ref,
          environment,
          source,
          status,
          commit_sha,
          dataset_version,
          recall_at_10,
          mrr,
          hit_in_answer_rate,
          groundedness,
          citation_coverage,
          refusal_precision,
          latency_p95_ms,
          avg_token_cost_usd,
          embedding_backlog,
          freshness_lag_p95_ms,
          artifact_url,
          error_message,
          created_at,
          completed_at
        )
        values (
          $1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
          $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21
        )
        on conflict (run_ref)
        do update set
          environment = excluded.environment,
          source = excluded.source,
          status = excluded.status,
          commit_sha = excluded.commit_sha,
          dataset_version = excluded.dataset_version,
          recall_at_10 = excluded.recall_at_10,
          mrr = excluded.mrr,
          hit_in_answer_rate = excluded.hit_in_answer_rate,
          groundedness = excluded.groundedness,
          citation_coverage = excluded.citation_coverage,
          refusal_precision = excluded.refusal_precision,
          latency_p95_ms = excluded.latency_p95_ms,
          avg_token_cost_usd = excluded.avg_token_cost_usd,
          embedding_backlog = excluded.embedding_backlog,
          freshness_lag_p95_ms = excluded.freshness_lag_p95_ms,
          artifact_url = excluded.artifact_url,
          error_message = excluded.error_message,
          created_at = excluded.created_at,
          completed_at = excluded.completed_at
      `,
      [
        record.id,
        record.runRef,
        record.environment,
        record.source,
        record.status,
        record.commitSha,
        record.datasetVersion,
        record.recallAt10,
        record.mrr,
        record.hitInAnswerRate,
        record.groundedness,
        record.citationCoverage,
        record.refusalPrecision,
        record.latencyP95Ms,
        record.avgTokenCostUsd,
        record.embeddingBacklog,
        record.freshnessLagP95Ms,
        record.artifactUrl,
        record.errorMessage,
        record.createdAt,
        record.completedAt
      ]
    );

    console.log(`Recorded evaluation_runs row for run_ref=${record.runRef} status=${record.status}`);
  } finally {
    await cleanup();
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
