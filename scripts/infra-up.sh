#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/require-docker-daemon.sh"
docker compose up -d postgres redis minio
"$repo_root/scripts/infra-check.sh"
docker compose ps
