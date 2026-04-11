# v5 / Phase 2B Status

## 1. Metadata

- `version`: `v5`
- `phase`: `Phase 2B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-11`

## 2. Goal

### In Scope

- 在 Phase 2A 可信问答闭环完成后，定义并冻结下一轮范围、验收与实施顺序，启动更完整的问答体验与检索可观测性规划。
- 扩展范围控制：`标签 / 来源类型 / 时间范围`
- 检索实验台增强：`lexical / semantic / rerank` 贡献、未入答原因、URL state
- 评估与运维面板增强：`recall / groundedness / citation coverage / latency / cost / backlog`
- 证据包增强：claim grouping、多引用组合、freshness 提示强化
- 问题历史与继续提问入口

### Out Of Scope

- 直接进入实现 lane 并修改 `Phase 2A` 已冻结 contract
- 开放互联网联网回答
- 浏览器插件
- 团队协作 / 权限模型
- 移动端
- 单文档摘要、多文档对比摘要、相关内容推荐
- 多模型路由与更细粒度模型策略

## 3. Milestones

- `foundation`: `in-progress`
- `api-persistence`: `not-started`
- `upload-storage`: `not-started`
- `worker-pipeline`: `not-started`
- `web-integration`: `not-started`
- `testing`: `not-started`
- `ci-cd`: `not-started`

## 4. Current Node

- `now`: 已完成 `v5 / Phase 2B` 的 handoff、status 与 planning exec plan 脚手架，并将 `current.md` 从已完成的 `v4 / Phase 2A` 切换到新的 active version。当前范围来源仍以 `Phase 2A` 的 `P1` backlog 与 `Phase 2B+` 候选能力为主，尚未冻结新的 `PRD / backlog / interaction delta / technical tradeoff`。
- `next`: 先完成 `Phase 2B` 的产品边界与优先级冻结，明确哪些能力进入本轮、哪些继续 deferred；随后再决定是否需要新的 `tech/*` 评估、交互稿和实现 freeze

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-11` 已完成 `v5 / Phase 2B` handoff/status/exec plan 脚手架初始化，并通过 `pnpm docs:check` 与 `git diff --check`；当前已知上一条稳定 main CI 为 `24221462669`
- `result`: `passed`
- `latest_failure`: `none`

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v5.md](/Users/coderlauu/xRag/docs/handoff/v5.md)
- `upstream_version`: [v4.md](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4-phase-2a.md](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)
- `upstream_product_docs`: [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `tech_docs`: [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- `exec_plans`: [Phase 2B planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-11-phase-2b-planning-and-design.md), [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-lanes.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md)
- `key_commits`: `66767af`
- `latest_ci_run`: `24221462669`（success，docs closeout commit 已完成 validate / integration / e2e / deploy-production / smoke-production）
