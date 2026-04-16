#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { openOpsFactDatabase } from "./lib/ops-fact-db.mjs";

function normalizeSmokeStatus(rawStatus, smokeExecuted) {
  if (!smokeExecuted) {
    return "unknown";
  }

  switch (rawStatus) {
    case "passed":
    case "failed":
    case "unknown":
      return rawStatus;
    case "success":
      return "passed";
    case "failure":
      return "failed";
    default:
      return "unknown";
  }
}

function optionalString(value) {
  return typeof value === "string" && value.length > 0 ? value : null;
}

async function loadEvidence() {
  const evidencePath = process.env.DEPLOY_EVIDENCE_JSON || process.argv[2];

  if (evidencePath) {
    const raw = await readFile(evidencePath, "utf8");
    return JSON.parse(raw);
  }

  const smokeExecuted = process.env.XRAG_SMOKE_EXECUTED !== "false";
  const rawSmokeStatus = process.env.XRAG_SMOKE_STATUS || "unknown";
  const capturedAt = new Date().toISOString();

  return {
    captured_at: capturedAt,
    deployed_at: process.env.XRAG_DEPLOYED_AT || capturedAt,
    smoke_at: smokeExecuted ? (process.env.XRAG_SMOKE_AT || capturedAt) : null,
    repository: process.env.GITHUB_REPOSITORY || "",
    run_id: process.env.GITHUB_RUN_ID || "",
    run_url:
      process.env.XRAG_DEPLOY_EVIDENCE_URL ||
      (process.env.GITHUB_REPOSITORY && process.env.GITHUB_RUN_ID
        ? `https://github.com/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}`
        : null),
    commit_sha: process.env.GITHUB_SHA || "",
    environment: process.env.DEPLOY_ENVIRONMENT || "unknown",
    smoke_status: normalizeSmokeStatus(rawSmokeStatus, smokeExecuted),
    smoke_executed: smokeExecuted,
    current_image_tag: process.env.XRAG_API_IMAGE || "",
    previous_stable_image_tag: process.env.XRAG_PREVIOUS_API_IMAGE || "",
    images: {
      api: process.env.XRAG_API_IMAGE || "",
      worker: process.env.XRAG_WORKER_IMAGE || "",
      web: process.env.XRAG_WEB_IMAGE || ""
    }
  };
}

function toRecord(evidence) {
  const environment = optionalString(evidence.environment);
  const currentImageTag = optionalString(evidence.current_image_tag || evidence.images?.api || "");

  if (!environment) {
    throw new Error("Deployment evidence is missing environment.");
  }

  if (!currentImageTag) {
    throw new Error("Deployment evidence is missing current_image_tag.");
  }

  const smokeExecuted = evidence.smoke_executed !== false;
  const smokeStatus = normalizeSmokeStatus(optionalString(evidence.smoke_status) || "unknown", smokeExecuted);

  return {
    id: randomUUID(),
    environment,
    commitSha: optionalString(evidence.commit_sha),
    workflowRunId: optionalString(evidence.run_id),
    currentImageTag,
    previousStableImageTag: optionalString(evidence.previous_stable_image_tag),
    smokeStatus,
    smokeAt: smokeStatus === "unknown" ? null : optionalString(evidence.smoke_at) || optionalString(evidence.captured_at),
    deployedAt: optionalString(evidence.deployed_at) || optionalString(evidence.captured_at) || new Date().toISOString(),
    evidenceUrl: optionalString(evidence.run_url)
  };
}

async function main() {
  const evidence = await loadEvidence();
  const record = toRecord(evidence);
  const { client, cleanup } = await openOpsFactDatabase();

  try {
    await client.query("begin");

    if (record.workflowRunId) {
      await client.query(
        "delete from deployment_records where environment = $1 and workflow_run_id = $2",
        [record.environment, record.workflowRunId]
      );
    }

    await client.query(
      `
        insert into deployment_records (
          id,
          environment,
          commit_sha,
          workflow_run_id,
          current_image_tag,
          previous_stable_image_tag,
          smoke_status,
          smoke_at,
          deployed_at,
          evidence_url
        )
        values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
      `,
      [
        record.id,
        record.environment,
        record.commitSha,
        record.workflowRunId,
        record.currentImageTag,
        record.previousStableImageTag,
        record.smokeStatus,
        record.smokeAt,
        record.deployedAt,
        record.evidenceUrl
      ]
    );

    await client.query("commit");

    console.log(
      `Recorded deployment_records row for environment=${record.environment} run_id=${record.workflowRunId ?? "n/a"} current_image=${record.currentImageTag}`
    );
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    await cleanup();
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
