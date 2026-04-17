#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

trap "$repo_root/scripts/test-env-down.sh" EXIT
source "$repo_root/scripts/require-docker-daemon.sh"

docker compose up -d --wait postgres redis
docker compose up -d minio
"$repo_root/scripts/infra-check.sh"

"$repo_root/apps/api/node_modules/.bin/tsx" apps/api/src/database/migrate.ts
cd "$repo_root/apps/web"
"$repo_root/apps/web/node_modules/.bin/playwright" install chromium
"$repo_root/apps/web/node_modules/.bin/playwright" test \
  e2e/inbox-search-detail.spec.ts \
  e2e/phase-2a-p0.spec.ts \
  e2e/phase-2b-lane-f.spec.ts \
  e2e/phase-3a-ops-diagnostics.spec.ts
