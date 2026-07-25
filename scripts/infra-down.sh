#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/require-docker-daemon.sh"

# 默认只停容器、保留数据卷——本地开发数据（尤其是手工建好的知识库/文档）不应该
# 在一次普通的"先停一下"里被顺手删掉。真的要清空数据卷时显式传 --wipe。
if [[ "${1:-}" == "--wipe" ]]; then
  docker compose down -v --remove-orphans
else
  docker compose down --remove-orphans
fi
