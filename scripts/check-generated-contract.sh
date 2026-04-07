#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

PATH="$repo_root/scripts/bin:$PATH" corepack pnpm --filter @xrag/api openapi:generate >/dev/null

if ! git diff --exit-code -- docs/generated/openapi/phase-1c-api.json >/dev/null; then
  echo "Generated OpenAPI artifact is out of date. Run \`pnpm contract:generate\` and commit the updated file." >&2
  git diff -- docs/generated/openapi/phase-1c-api.json >&2 || true
  exit 1
fi
