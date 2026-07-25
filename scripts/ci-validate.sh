#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if command -v docker >/dev/null 2>&1; then
  docker compose config >/dev/null
else
  echo "[WARN] docker CLI not found; skipping 'docker compose config' check." >&2
fi

node "$repo_root/scripts/check-doc-links.mjs"
node "$repo_root/scripts/check-deploy-contract.mjs"

(cd frontend && corepack pnpm install --frozen-lockfile && corepack pnpm run build)

# CI (actions/setup-java) 已经把 JAVA_HOME 指向 JDK 17；本机如果没有显式设置，
# 尝试用 macOS 的 java_home 自动定位一个 JDK 17，避免默认 JDK 版本不对导致构建失败。
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  export JAVA_HOME
fi

(cd backend && ./mvnw -q -B verify)
