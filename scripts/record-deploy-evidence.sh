#!/usr/bin/env bash
set -euo pipefail

artifact_dir="${ARTIFACT_DIR:-artifacts/deploy-evidence}"
environment_name="${DEPLOY_ENVIRONMENT:-unknown}"
raw_smoke_status="${XRAG_SMOKE_STATUS:-unknown}"
smoke_executed="${XRAG_SMOKE_EXECUTED:-true}"
base_url="${APP_BASE_URL:-}"
timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
deployed_at="${XRAG_DEPLOYED_AT:-$timestamp}"
run_id="${GITHUB_RUN_ID:-}"
run_url=""

normalize_smoke_status() {
  local raw_status="${1:-unknown}"
  local executed="${2:-true}"

  if [[ "${executed}" != "true" ]]; then
    echo "unknown"
    return 0
  fi

  case "${raw_status}" in
    passed | failed | unknown)
      echo "${raw_status}"
      ;;
    success)
      echo "passed"
      ;;
    failure)
      echo "failed"
      ;;
    *)
      echo "unknown"
      ;;
  esac
}

smoke_status="$(normalize_smoke_status "${raw_smoke_status}" "${smoke_executed}")"
smoke_at=""
if [[ "${smoke_status}" != "unknown" ]]; then
  smoke_at="${timestamp}"
fi

if [[ -n "${run_id}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
  run_url="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${run_id}"
fi

mkdir -p "$artifact_dir"

json_path="$artifact_dir/evidence.json"
md_path="$artifact_dir/evidence.md"

XRAG_CAPTURED_AT="${timestamp}" \
XRAG_DEPLOYED_AT_RESOLVED="${deployed_at}" \
XRAG_SMOKE_AT_RESOLVED="${smoke_at}" \
XRAG_RUN_URL="${run_url}" \
XRAG_SMOKE_STATUS_RESOLVED="${smoke_status}" \
python3 - <<'PY' >"$json_path"
import json
import os

def optional(value: str):
    return value if value else None

payload = {
    "captured_at": os.environ["XRAG_CAPTURED_AT"],
    "deployed_at": os.environ["XRAG_DEPLOYED_AT_RESOLVED"],
    "smoke_at": optional(os.environ.get("XRAG_SMOKE_AT_RESOLVED", "")),
    "repository": os.environ.get("GITHUB_REPOSITORY", ""),
    "run_id": os.environ.get("GITHUB_RUN_ID", ""),
    "run_url": optional(os.environ.get("XRAG_RUN_URL", "")),
    "commit_sha": os.environ.get("GITHUB_SHA", ""),
    "environment": os.environ.get("DEPLOY_ENVIRONMENT", "unknown"),
    "smoke_status": os.environ["XRAG_SMOKE_STATUS_RESOLVED"],
    "smoke_executed": os.environ.get("XRAG_SMOKE_EXECUTED", "true") == "true",
    "base_url": optional(os.environ.get("APP_BASE_URL", "")),
    "current_image_tag": optional(os.environ.get("XRAG_API_IMAGE", "")),
    "previous_stable_image_tag": optional(os.environ.get("XRAG_PREVIOUS_API_IMAGE", "")),
    "images": {
        "api": optional(os.environ.get("XRAG_API_IMAGE", "")),
        "worker": optional(os.environ.get("XRAG_WORKER_IMAGE", "")),
        "web": optional(os.environ.get("XRAG_WEB_IMAGE", "")),
    },
    "rollback_hint": "Redeploy the previous known-good image tag or re-run the workflow after restoring the failing environment dependency.",
}

json.dump(payload, fp=os.sys.stdout, ensure_ascii=True, indent=2)
os.sys.stdout.write("\n")
PY

{
  echo "# Deploy Evidence"
  echo
  echo "- Environment: \`${environment_name}\`"
  echo "- Captured At (UTC): \`${timestamp}\`"
  echo "- Deployed At (UTC): \`${deployed_at}\`"
  echo "- GitHub Run ID: \`${run_id:-unknown}\`"
  echo "- Commit: \`${GITHUB_SHA:-unknown}\`"
  echo "- Smoke Status: \`${smoke_status}\`"
  if [[ -n "$smoke_at" ]]; then
    echo "- Smoke At (UTC): \`${smoke_at}\`"
  fi
  if [[ -n "$run_url" ]]; then
    echo "- Run URL: ${run_url}"
  fi
  if [[ -n "$base_url" ]]; then
    echo "- Base URL: ${base_url}"
  fi
  if [[ -n "${XRAG_API_IMAGE:-}" || -n "${XRAG_WORKER_IMAGE:-}" || -n "${XRAG_WEB_IMAGE:-}" ]]; then
    echo
    echo "## Images"
    echo
    [[ -n "${XRAG_API_IMAGE:-}" ]] && echo "- API: \`${XRAG_API_IMAGE}\`"
    [[ -n "${XRAG_PREVIOUS_API_IMAGE:-}" ]] && echo "- Previous Stable API: \`${XRAG_PREVIOUS_API_IMAGE}\`"
    [[ -n "${XRAG_WORKER_IMAGE:-}" ]] && echo "- Worker: \`${XRAG_WORKER_IMAGE}\`"
    [[ -n "${XRAG_WEB_IMAGE:-}" ]] && echo "- Web: \`${XRAG_WEB_IMAGE}\`"
  fi
  echo
  echo "## Rollback Hint"
  echo
  echo "Redeploy the previous known-good image tag or restore the target environment dependency before rerunning the workflow."
} >"$md_path"

echo "Recorded deploy evidence in ${artifact_dir}"
