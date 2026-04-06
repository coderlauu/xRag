# v2 / Phase 1B Status

## 1. Metadata

- `version`: `v2`
- `phase`: `Phase 1B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-06`

## 2. Goal

### In Scope

- 支持 `pdf` 真实解析
- 支持 multipart 大文件上传
- 增强上传与解析失败诊断
- 建立 production 观测与回滚基线

### Out Of Scope

- AI 问答
- 语义搜索
- OCR
- 浏览器插件
- 团队协作
- 移动端

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `completed`
- `upload-storage`: `in-progress`
- `worker-pipeline`: `in-progress`
- `web-integration`: `in-progress`
- `testing`: `in-progress`
- `ci-cd`: `completed`
- `contract-freeze`: `completed`

## 4. Current Node

- `now`: 已完成 `Lane A` 的 multipart 完成校验与 integration 证据补强，并接通 `Lane B` 的文本型 PDF 解析基线和 `Lane C` 的 web multipart 上传入口
- `next`: 继续补 `pdf` 解析成功/失败的更细验证，并推进 diagnostics / ops 读接口接入

## 5. Blockers

- `blocker`: 当前 `Lane B / Lane C` 仍缺少 `pdf` 解析结果在前端详情页和搜索页中的完整呈现，以及 `Ops Board` 的真实读接口联调
  - `impact`: `Phase 1B` 的“可规模化使用”目标还未完全闭环，尤其是失败诊断与运维可见性
  - `owner`: `codex`
- `secondary_blocker`: 当前 worker 只完成了文本型 PDF 解析基线，尚未补 `pdf_parse_unsupported / pdf_parse_timeout` 的更细测试证据
  - `impact`: 失败分类已定义，但 `Phase 1B` 的 parser lane 还缺更强的回归保护
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-06` 已通过 `pnpm validate`、`pnpm --filter @xrag/shared-types typecheck`、`pnpm --filter @xrag/api typecheck`、`pnpm --filter @xrag/api openapi:generate`、`./scripts/run-api-integration.sh`、`pnpm --filter @xrag/worker test:unit`、`pnpm --filter @xrag/worker build`、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/web build`
- `result`: `passed`
- `latest_failure`: `CI` 失败闭环仍待线上自动归档验证；`pdf` 失败分类的更细粒度测试尚未补齐

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `prototype`: [prototype/v2](/Users/coderlauu/xRag/prototype/v2/index.html)
- `interaction_spec`: [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- `tech_docs`: [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md), [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `generated_contract`: [Phase 1B OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1b-api.json)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-02-phase-1b-planning-and-design.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-04-ci-failure-loop.md), [Phase 1B prototype and interaction](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-prototype-and-interaction.md), [Phase 1B harness hardening](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-harness-hardening.md), [Phase 1B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-implementation-lanes.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `431e4cc`, `62401b9`, `b997aea`
- `latest_ci_run`: `23969230230`
