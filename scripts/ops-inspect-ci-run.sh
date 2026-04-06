#!/usr/bin/env bash
set -euo pipefail

run_id="${1:-}"
if [[ -z "$run_id" ]]; then
  echo "Usage: $0 <github-actions-run-id>" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required." >&2
  exit 1
fi

repo="${GITHUB_REPOSITORY:-}"
if [[ -z "$repo" ]]; then
  repo="$(git config --get remote.origin.url | sed -E 's#(git@github.com:|https://github.com/)##; s#\\.git$##')"
fi

if [[ -z "$repo" ]]; then
  echo "Unable to determine GitHub repository. Set GITHUB_REPOSITORY." >&2
  exit 1
fi

artifact_dir="${OUTPUT_DIR:-artifacts/ci-run/$run_id}"
mkdir -p "$artifact_dir"

gh api "repos/${repo}/actions/runs/${run_id}" >"$artifact_dir/run.json"
gh api "repos/${repo}/actions/runs/${run_id}/jobs?per_page=100" >"$artifact_dir/jobs.json"
gh run view "$run_id" --repo "$repo" >"$artifact_dir/summary.txt"

echo "Saved CI run inspection output to $artifact_dir"
echo
sed -n '1,160p' "$artifact_dir/summary.txt"
