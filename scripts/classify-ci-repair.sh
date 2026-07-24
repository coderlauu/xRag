#!/usr/bin/env bash
set -euo pipefail

failed_log_path="${1:-}"

if [[ -z "$failed_log_path" ]]; then
  echo "Usage: $0 <failed-log-path>" >&2
  exit 1
fi

if [[ ! -f "$failed_log_path" ]]; then
  echo "Failed log file not found: $failed_log_path" >&2
  exit 1
fi

repair_type="unsupported"
repair_supported="false"
repair_reason="No supported repair rule matched the failed log."

if grep -Fq "ERR_PNPM_OUTDATED_LOCKFILE" "$failed_log_path"; then
  repair_type="outdated_lockfile"
  repair_supported="true"
  repair_reason="frontend/pnpm-lock.yaml is stale compared with frontend/package.json."
fi

cat <<EOF
REPAIR_TYPE=${repair_type}
REPAIR_SUPPORTED=${repair_supported}
REPAIR_REASON=$(printf '%q' "$repair_reason")
EOF
