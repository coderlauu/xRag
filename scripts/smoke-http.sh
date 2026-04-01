#!/usr/bin/env bash
set -euo pipefail

base_url="${1:?base url is required}"

wait_for() {
  local name="$1"
  local attempts="$2"
  shift 2

  local try
  for try in $(seq 1 "${attempts}"); do
    if "$@"; then
      return 0
    fi

    if [[ "${try}" -lt "${attempts}" ]]; then
      sleep 3
    fi
  done

  echo "Timed out waiting for ${name}" >&2
  return 1
}

wait_for "web root" 40 curl --connect-timeout 5 --max-time 15 -fsS "${base_url}/" >/dev/null
wait_for "api health" 40 curl --connect-timeout 5 --max-time 15 -fsS "${base_url}/api/v1/health" >/dev/null
wait_for "api ready" 40 curl --connect-timeout 5 --max-time 15 -fsS "${base_url}/api/v1/health/ready" >/dev/null
