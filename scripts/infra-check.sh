#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/require-docker-daemon.sh"
docker compose exec -T postgres pg_isready -U xrag -d xrag
docker compose exec -T redis redis-cli ping
curl -fsS http://localhost:9000/minio/health/ready >/dev/null
