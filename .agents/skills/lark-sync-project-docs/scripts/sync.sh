#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
publisher="$repo_root/scripts/publish-feishu-docs.mjs"
config="$repo_root/.feishu-docs.local.json"

if [[ ! -f "$publisher" ]]; then
  echo "Publisher not found: $publisher" >&2
  exit 1
fi

if [[ ! -f "$config" ]]; then
  echo "Local Feishu mapping not found: $config" >&2
  echo "Create it from .feishu-docs.example.json before syncing." >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is required to run the Feishu document publisher." >&2
  exit 1
fi

if ! command -v lark-cli >/dev/null 2>&1; then
  echo "lark-cli is required to publish documents to Feishu." >&2
  exit 1
fi

exec node "$publisher" "$@"
