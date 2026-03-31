#!/usr/bin/env bash
set -euo pipefail

skill_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target_base="${CODEX_HOME:-$HOME/.codex}/skills"
target_dir="${target_base}/$(basename "$skill_dir")"

mkdir -p "$target_base"

if [[ -L "$target_dir" && "$(readlink "$target_dir")" == "$skill_dir" ]]; then
  echo "[OK] skill already installed at $target_dir"
  exit 0
fi

if [[ -e "$target_dir" && ! -L "$target_dir" ]]; then
  echo "[ERROR] target exists and is not a symlink: $target_dir" >&2
  exit 1
fi

rm -f "$target_dir"
ln -s "$skill_dir" "$target_dir"
echo "[OK] installed skill to $target_dir"
