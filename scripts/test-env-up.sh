#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/require-docker-daemon.sh"

docker compose up -d --wait postgres redis
docker compose up -d minio
"$repo_root/scripts/infra-check.sh"
PATH="$repo_root/scripts/bin:$PATH" corepack pnpm --filter @xrag/api db:migrate
