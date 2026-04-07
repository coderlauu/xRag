# v2 / Phase 1B Status

## 1. Metadata

- `version`: `v2`
- `phase`: `Phase 1B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-07`

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
- `upload-storage`: `completed`
- `worker-pipeline`: `in-progress`
- `web-integration`: `completed`
- `testing`: `in-progress`
- `ci-cd`: `completed`
- `contract-freeze`: `completed`

## 4. Current Node

- `now`: 线上发现上传 PDF 后出现 `Cannot transfer object of unsupported type.`，说明真实解析器 runtime 仍有生产缺陷；当前已重新打开 `Phase 1B`，以热修方式处理
- `next`: 修复真实 PDF parser runtime 错误、补齐生产同构回归证据，再重新评估 `Phase 1B` 是否可关闭

## 5. Blockers

- `blocker`: 生产环境上传 PDF 后解析失败，报错 `Cannot transfer object of unsupported type.`
  - `impact`: `Phase 1B` 的“PDF 真实解析闭环”在生产事实层面未完全成立，版本不能视为最终完成
  - `owner`: `codex`
- `secondary_blocker`: 当前 PDF integration 证据通过解析结果注入完成，未覆盖真实 `pdf-parse` runtime 行为
  - `impact`: 测试证据与生产运行环境存在缝隙，导致这次缺陷未在版本关闭前暴露
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-07` 已通过 `pnpm validate`、`pnpm --filter @xrag/shared-types typecheck`、`pnpm --filter @xrag/api typecheck`、`pnpm --filter @xrag/api openapi:generate`、`./scripts/run-api-integration.sh`、`pnpm --filter @xrag/worker test:unit`、`pnpm --filter @xrag/worker build`、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/web build`、`./scripts/run-e2e-smoke.sh`；最近全绿 GitHub Actions 基线为 `24061270458`
- `result`: `passed`
- `latest_failure`: 最新生产缺陷是上传 PDF 后出现 `Cannot transfer object of unsupported type.`；另一个最近已收口的 CI 失败为 `run 24060795722` 的 `Playwright` selector 与详情页中文文案漂移

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `prototype`: [prototype/v2](/Users/coderlauu/xRag/prototype/v2/index.html)
- `interaction_spec`: [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- `tech_docs`: [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md), [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `generated_contract`: [Phase 1B OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1b-api.json)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-02-phase-1b-planning-and-design.md), [Phase 1B prototype and interaction](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-prototype-and-interaction.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-04-ci-failure-loop.md), [CI auto repair loop](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-ci-auto-repair-loop.md), [Phase 1B harness hardening](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-harness-hardening.md), [Phase 1B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-implementation-lanes.md), [Phase 1B PDF parser runtime hotfix](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-1b-pdf-parser-runtime-hotfix.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `62401b9`, `b997aea`, `c20835e`, `e3832ef`, `cff142e`, `15a7ab2`, `85cf00a`
- `latest_ci_run`: `24060968289`
