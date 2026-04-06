#!/usr/bin/env bash
set -euo pipefail

artifact_dir="${ARTIFACT_DIR:-artifacts/deploy-evidence}"
environment_name="${DEPLOY_ENVIRONMENT:-unknown}"
smoke_status="${XRAG_SMOKE_STATUS:-unknown}"
base_url="${APP_BASE_URL:-}"
timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

mkdir -p "$artifact_dir"

json_path="$artifact_dir/evidence.json"
md_path="$artifact_dir/evidence.md"

jq -n \
  --arg captured_at "$timestamp" \
  --arg repository "${GITHUB_REPOSITORY:-}" \
  --arg run_id "${GITHUB_RUN_ID:-}" \
  --arg sha "${GITHUB_SHA:-}" \
  --arg environment "$environment_name" \
  --arg smoke_status "$smoke_status" \
  --arg base_url "$base_url" \
  --arg api_image "${XRAG_API_IMAGE:-}" \
  --arg worker_image "${XRAG_WORKER_IMAGE:-}" \
  --arg web_image "${XRAG_WEB_IMAGE:-}" \
  '
  {
    captured_at: $captured_at,
    repository: $repository,
    run_id: $run_id,
    commit_sha: $sha,
    environment: $environment,
    smoke_status: $smoke_status,
    base_url: ($base_url | select(length > 0)),
    images: {
      api: ($api_image | select(length > 0)),
      worker: ($worker_image | select(length > 0)),
      web: ($web_image | select(length > 0))
    },
    rollback_hint: "Redeploy the previous known-good image tag or re-run the workflow after restoring the failing environment dependency."
  }
  ' >"$json_path"

{
  echo "# Deploy Evidence"
  echo
  echo "- Environment: \`${environment_name}\`"
  echo "- Captured At (UTC): \`${timestamp}\`"
  echo "- GitHub Run ID: \`${GITHUB_RUN_ID:-unknown}\`"
  echo "- Commit: \`${GITHUB_SHA:-unknown}\`"
  echo "- Smoke Status: \`${smoke_status}\`"
  if [[ -n "$base_url" ]]; then
    echo "- Base URL: ${base_url}"
  fi
  if [[ -n "${XRAG_API_IMAGE:-}" || -n "${XRAG_WORKER_IMAGE:-}" || -n "${XRAG_WEB_IMAGE:-}" ]]; then
    echo
    echo "## Images"
    echo
    [[ -n "${XRAG_API_IMAGE:-}" ]] && echo "- API: \`${XRAG_API_IMAGE}\`"
    [[ -n "${XRAG_WORKER_IMAGE:-}" ]] && echo "- Worker: \`${XRAG_WORKER_IMAGE}\`"
    [[ -n "${XRAG_WEB_IMAGE:-}" ]] && echo "- Web: \`${XRAG_WEB_IMAGE}\`"
  fi
  echo
  echo "## Rollback Hint"
  echo
  echo "Redeploy the previous known-good image tag or restore the target environment dependency before rerunning the workflow."
} >"$md_path"

echo "Recorded deploy evidence in ${artifact_dir}"
