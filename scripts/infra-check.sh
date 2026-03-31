#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/require-docker-daemon.sh"

wait_for() {
  local name="$1"
  local attempts="$2"
  shift 2

  local try
  for try in $(seq 1 "$attempts"); do
    if "$@"; then
      return 0
    fi

    if [[ "$try" -lt "$attempts" ]]; then
      sleep 2
    fi
  done

  echo "Timed out waiting for ${name}" >&2
  return 1
}

wait_for "postgres" 15 docker compose exec -T postgres pg_isready -U xrag -d xrag
wait_for "redis" 15 docker compose exec -T redis redis-cli ping
wait_for "minio" 15 curl -fsS http://localhost:9000/minio/health/ready >/dev/null
