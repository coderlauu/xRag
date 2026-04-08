#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

node_bin="${NODE_BIN:-/opt/homebrew/bin/node}"
(
  cd apps/api
  "$node_bin" node_modules/tsx/dist/cli.mjs src/openapi.ts >/dev/null
)

if ! git diff --exit-code -- docs/generated/openapi/phase-2a-api.json >/dev/null; then
  echo "Generated OpenAPI artifact is out of date. Run \`pnpm contract:generate\` and commit the updated file." >&2
  git diff -- docs/generated/openapi/phase-2a-api.json >&2 || true
  exit 1
fi
