#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

corepack pnpm install --frozen-lockfile
docker compose config >/dev/null
export PATH="$repo_root/scripts/bin:$PATH"
corepack pnpm validate
