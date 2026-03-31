#!/usr/bin/env bash
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  echo "[ERROR] docker CLI not found. Install Docker Desktop or Docker Engine." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "[ERROR] Docker daemon is not running or not reachable." >&2
  echo "        Start Docker Desktop or your Docker daemon, then retry." >&2
  exit 1
fi
