#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

trap "$repo_root/scripts/test-env-down.sh" EXIT
"$repo_root/scripts/test-env-up.sh"
PATH="$repo_root/scripts/bin:$PATH" corepack pnpm --filter @xrag/web exec playwright install chromium
PATH="$repo_root/scripts/bin:$PATH" corepack pnpm --filter @xrag/web test:e2e
