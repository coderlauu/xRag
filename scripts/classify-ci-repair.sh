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

if grep -Fq "Generated OpenAPI artifact is out of date" "$failed_log_path"; then
  repair_type="openapi_contract_out_of_date"
  repair_supported="true"
  repair_reason="Generated OpenAPI artifact is out of date."
elif grep -Fq "ERR_PNPM_OUTDATED_LOCKFILE" "$failed_log_path"; then
  repair_type="outdated_lockfile"
  repair_supported="true"
  repair_reason="Lockfile is stale compared with package manifests."
elif grep -Fq "waiting for getByLabel('Search documents')" "$failed_log_path" || grep -Fq 'name: "Search"' "$failed_log_path"; then
  repair_type="search_page_e2e_selector_drift"
  repair_supported="true"
  repair_reason="Playwright search page selector drift detected."
fi

cat <<EOF
REPAIR_TYPE=${repair_type}
REPAIR_SUPPORTED=${repair_supported}
REPAIR_REASON=$(printf '%q' "$repair_reason")
EOF
