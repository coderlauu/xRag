#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

docker compose config >/dev/null
node "$repo_root/scripts/check-doc-links.mjs"

(cd frontend && corepack pnpm install --frozen-lockfile && corepack pnpm run build)
(cd backend && mvn -q -B verify)
