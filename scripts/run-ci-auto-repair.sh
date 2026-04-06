#!/usr/bin/env bash
set -euo pipefail

required_envs=(
  GH_TOKEN
  GITHUB_REPOSITORY
  SOURCE_RUN_ID
  SOURCE_SHA
)

for name in "${required_envs[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "${name} is required." >&2
    exit 1
  fi
done

output_dir="${OUTPUT_DIR:-artifacts/ci-auto-repair}"
context_dir="${output_dir}/failure-context"
summary_json="${output_dir}/repair-summary.json"
summary_md="${output_dir}/repair-summary.md"

mkdir -p "${output_dir}"

OUTPUT_DIR="${context_dir}" scripts/collect-ci-failure-context.sh

eval "$(scripts/classify-ci-repair.sh "${context_dir}/failed.log")"

write_summary() {
  local status="$1"
  local branch_name="$2"
  local pr_url="$3"

  jq -n \
    --arg status "$status" \
    --arg repair_type "$REPAIR_TYPE" \
    --arg supported "$REPAIR_SUPPORTED" \
    --arg reason "$REPAIR_REASON" \
    --arg source_run_id "$SOURCE_RUN_ID" \
    --arg source_sha "$SOURCE_SHA" \
    --arg branch_name "$branch_name" \
    --arg pr_url "$pr_url" \
    '{
      status: $status,
      repair_type: $repair_type,
      repair_supported: ($supported == "true"),
      reason: $reason,
      source_run_id: $source_run_id,
      source_sha: $source_sha,
      repair_branch: $branch_name,
      pull_request_url: $pr_url
    }' >"${summary_json}"

  {
    echo "# CI Auto Repair Summary"
    echo
    echo "- 状态：\`${status}\`"
    echo "- 来源 Run：\`${SOURCE_RUN_ID}\`"
    echo "- 来源 Commit：\`${SOURCE_SHA:0:7}\`"
    echo "- 修复类型：\`${REPAIR_TYPE}\`"
    echo "- 是否支持：\`${REPAIR_SUPPORTED}\`"
    echo "- 说明：${REPAIR_REASON}"
    if [[ -n "${branch_name}" ]]; then
      echo "- 修复分支：\`${branch_name}\`"
    fi
    if [[ -n "${pr_url}" ]]; then
      echo "- PR：${pr_url}"
    fi
  } >"${summary_md}"
}

git fetch origin main

main_sha="$(git rev-parse origin/main)"
if [[ "${main_sha}" != "${SOURCE_SHA}" ]]; then
  REPAIR_TYPE="stale_source_sha"
  REPAIR_SUPPORTED="false"
  REPAIR_REASON="Source SHA is no longer the latest commit on origin/main; skip auto repair."
  write_summary "skipped" "" ""
  exit 0
fi

if [[ "${REPAIR_SUPPORTED}" != "true" ]]; then
  write_summary "unsupported" "" ""
  exit 0
fi

branch_name="auto-repair/run-${SOURCE_RUN_ID}-${REPAIR_TYPE}"
existing_pr_url="$(gh pr list --repo "${GITHUB_REPOSITORY}" --head "${branch_name}" --json url --jq '.[0].url // ""')"
if [[ -n "${existing_pr_url}" ]]; then
  REPAIR_REASON="Repair branch already exists."
  write_summary "existing_pr" "${branch_name}" "${existing_pr_url}"
  exit 0
fi

if git ls-remote --exit-code --heads origin "${branch_name}" >/dev/null 2>&1; then
  REPAIR_REASON="Repair branch already exists on origin."
  write_summary "existing_branch" "${branch_name}" ""
  exit 0
fi

git checkout -b "${branch_name}"

case "${REPAIR_TYPE}" in
  openapi_contract_out_of_date)
    corepack pnpm install --frozen-lockfile
    corepack pnpm contract:generate
    corepack pnpm contract:check
    ;;
  outdated_lockfile)
    corepack pnpm install --no-frozen-lockfile
    corepack pnpm install --frozen-lockfile
    ;;
  search_page_e2e_selector_drift)
    corepack pnpm install --frozen-lockfile
    node scripts/fix-search-e2e-selector-drift.mjs
    corepack pnpm --filter @xrag/web typecheck
    ;;
  *)
    echo "Unsupported repair type: ${REPAIR_TYPE}" >&2
    exit 1
    ;;
esac

if git diff --quiet; then
  REPAIR_REASON="Repair type matched, but no file changes were needed."
  write_summary "no_changes" "${branch_name}" ""
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

git add -A
git commit -m "fix(ci): auto repair ${REPAIR_TYPE} for run ${SOURCE_RUN_ID}"
git push --set-upstream origin "${branch_name}"

cat >"${output_dir}/pr-body.md" <<EOF
## 自动修复说明

- 来源 CI Run：\`${SOURCE_RUN_ID}\`
- 来源 Commit：\`${SOURCE_SHA:0:7}\`
- 修复类型：\`${REPAIR_TYPE}\`
- 触发原因：${REPAIR_REASON}

## 自动验证

- 已执行对应最小验证
- 详细上下文见 artifact：\`ci-auto-repair-${SOURCE_RUN_ID}\`

## 注意事项

- 本 PR 由自动修复流程创建
- 默认不自动 merge
- 合并后仍由主 \`CI/CD\` 再次验证
EOF

pr_url="$(gh pr create \
  --repo "${GITHUB_REPOSITORY}" \
  --base main \
  --head "${branch_name}" \
  --title "[Auto Repair] ${REPAIR_TYPE} for CI run ${SOURCE_RUN_ID}" \
  --body-file "${output_dir}/pr-body.md")"

write_summary "created_pr" "${branch_name}" "${pr_url}"
