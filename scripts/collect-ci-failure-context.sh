#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required." >&2
  exit 1
fi

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY is required." >&2
  exit 1
fi

if [[ -z "${SOURCE_RUN_ID:-}" ]]; then
  echo "SOURCE_RUN_ID is required." >&2
  exit 1
fi

output_dir="${OUTPUT_DIR:-artifacts/ci-failure}"
captured_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

mkdir -p "$output_dir"

run_json="$output_dir/run.json"
jobs_json="$output_dir/jobs.json"
failed_log="$output_dir/failed.log"
incident_json="$output_dir/incident.json"
incident_md="$output_dir/incident.md"

gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${SOURCE_RUN_ID}" >"$run_json"
gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${SOURCE_RUN_ID}/jobs?per_page=100" >"$jobs_json"

if ! gh run view "$SOURCE_RUN_ID" --repo "$GITHUB_REPOSITORY" --log-failed >"$failed_log" 2>"$output_dir/failed.log.stderr"; then
  echo "Failed to fetch failed logs for run ${SOURCE_RUN_ID}; see failed.log.stderr for details." >"$failed_log"
fi

CAPTURED_AT="$captured_at" jq -n \
  --slurpfile run "$run_json" \
  --slurpfile jobs "$jobs_json" \
  '
  ($run[0]) as $run |
  ($jobs[0].jobs // []) as $jobs |
  {
    captured_at: env.CAPTURED_AT,
    repository: env.GITHUB_REPOSITORY,
    run: {
      id: $run.id,
      attempt: $run.run_attempt,
      number: $run.run_number,
      workflow_name: $run.name,
      event: $run.event,
      status: $run.status,
      conclusion: $run.conclusion,
      head_branch: $run.head_branch,
      head_sha: $run.head_sha,
      actor: ($run.actor.login // null),
      html_url: $run.html_url,
      created_at: $run.created_at,
      updated_at: $run.updated_at
    },
    failed_jobs: (
      $jobs
      | map(
          select(.conclusion == "failure" or .conclusion == "cancelled" or .conclusion == "timed_out")
          | {
              id,
              name,
              status,
              conclusion,
              started_at,
              completed_at,
              html_url,
              failed_steps: (
                [.steps[]? | select(.conclusion == "failure" or .conclusion == "cancelled" or .conclusion == "timed_out")
                  | {
                      number,
                      name,
                      status,
                      conclusion,
                      started_at,
                      completed_at
                    }]
              )
            }
        )
    )
  }
  ' >"$incident_json"

short_sha="$(jq -r '.run.head_sha[0:7]' "$incident_json")"

{
  echo "# CI Failure Incident"
  echo
  echo "- Repository: \`${GITHUB_REPOSITORY}\`"
  echo "- Workflow: \`$(jq -r '.run.workflow_name' "$incident_json")\`"
  echo "- Run ID: \`${SOURCE_RUN_ID}\`"
  echo "- Run Attempt: \`$(jq -r '.run.attempt' "$incident_json")\`"
  echo "- Branch: \`$(jq -r '.run.head_branch' "$incident_json")\`"
  echo "- Commit: \`${short_sha}\`"
  echo "- Event: \`$(jq -r '.run.event' "$incident_json")\`"
  echo "- Conclusion: \`$(jq -r '.run.conclusion' "$incident_json")\`"
  echo "- Run URL: $(jq -r '.run.html_url' "$incident_json")"
  echo "- Captured At (UTC): \`$captured_at\`"
  echo
  echo "## Failed Jobs"
  echo

  if [[ "$(jq '.failed_jobs | length' "$incident_json")" -eq 0 ]]; then
    echo "- No failed jobs were returned by the Actions API."
  else
    while IFS=$'\t' read -r job_name job_conclusion job_url failed_steps; do
      echo "- ${job_name} (\`${job_conclusion}\`)"
      echo "  - Job URL: ${job_url}"
      if [[ -n "$failed_steps" ]]; then
        echo "  - Failed steps: ${failed_steps}"
      else
        echo "  - Failed steps: none reported"
      fi
    done < <(
      jq -r '
        .failed_jobs[]
        | [
            .name,
            .conclusion,
            .html_url,
            ((.failed_steps | map(.name) | join(", ")) // "")
          ]
        | @tsv
      ' "$incident_json"
    )
  fi

  echo
  echo "## Suggested Repair Loop"
  echo
  echo "1. Open the failed run and confirm the failing job/step."
  echo "2. Inspect \`incident.json\` and \`failed.log\` from this artifact."
  echo "3. Patch the repo, validate locally, then push and rerun \`CI\`."
  echo
  echo "## Failed Log Extract"
  echo
  echo '```text'
  sed -n '1,400p' "$failed_log"
  echo '```'
} >"$incident_md"

echo "Collected CI failure context in ${output_dir}"
