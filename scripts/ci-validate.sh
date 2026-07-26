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

# 本机跑集成测试之前，先确认没有另一个后端实例连着同一个库。
#
# 为什么值得专门加这一道：那个后端的 IngestionDispatcher 会和测试实例**抢同一张任务表**，
# 抢到之后用它自己的配置去执行（通常没有 Embedding Key，于是失败），测试这边看到的就是
# 十几条 "expected SUCCESS but was FAILED" 的超时。**这个症状长得完全不像它的成因**——
# 它像业务代码坏了，实际是环境里多了一个进程。这个坑在本项目已经踩了三次，其中两次是
# IDE 里启动的后端（不在终端里，很容易忘记它还开着）。
#
# 根因是 ADR 0002 的单实例假设：docs/adr/0002-knowledge-base-async-and-concurrency.md
# CI 上不会有这个进程，这段检查在那里是个空操作。
backend_port="${PORT:-3001}"
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$backend_port" -sTCP:LISTEN >/dev/null 2>&1; then
  cat >&2 <<EOF
[ERROR] 端口 ${backend_port} 上已经有一个后端在运行，集成测试会与它抢任务表。

  它的 IngestionDispatcher 会抢走测试建的入库任务并用自己的配置执行，
  测试这边表现为成片的 "expected SUCCESS but was FAILED" 超时——看起来像代码坏了，
  实际只是环境里多了一个进程。详见 ADR 0002 的单实例假设。

  请先停掉它再重跑（注意 IDE 里启动的后端不在终端里，容易被忘掉）：
    lsof -nP -iTCP:${backend_port} -sTCP:LISTEN
EOF
  exit 1
fi

# CI (actions/setup-java) 已经把 JAVA_HOME 指向 JDK 17；本机如果没有显式设置，
# 尝试用 macOS 的 java_home 自动定位一个 JDK 17，避免默认 JDK 版本不对导致构建失败。
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  export JAVA_HOME
fi

(cd backend && ./mvnw -q -B verify)
