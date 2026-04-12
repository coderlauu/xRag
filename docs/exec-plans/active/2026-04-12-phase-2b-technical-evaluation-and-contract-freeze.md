# Phase 2B Technical Evaluation And Contract Freeze

## 1. Metadata

- `plan_id`: `phase-2b-technical-evaluation-and-contract-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v5 handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [Phase 2B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md), [Phase 2B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md), [Phase 2B api design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md), [Phase 2B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md), [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md), [Phase 2B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md), [v5 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md), [Phase 2B P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

## 2. Objective

把 `Phase 2B` 已冻结的产品范围转成可以进入实现前冻结的技术基线，明确 `scope / history / retrieval explain / evidence grouping / ops` 的正式 contract 边界。

## 3. Scope

### In Scope

- 对照当前已实现的 `Phase 2A` 代码基线，确认 `Phase 2B` 是增量增强而不是重做
- 产出 `architecture / data-model / api` 三份技术方案评估文档
- 产出进入正式 contract freeze 的前置判断文档
- 冻结下一步必须一起收口的 `schema / shared-types / API / 状态机 / web state` 变更面

### Out Of Scope

- 直接改动运行时代码
- 提前拆 implementation lanes
- 提前扩展 `ops` 到阻塞主链的程度
- 重新讨论已在 `Phase 2B P0 Technical Tradeoffs` 排除的方向

## 4. Assumptions

- `Phase 2A` 现实工程基线已经存在，且继续作为 release-ready 与 production 排障基线
- `Phase 2B` 首先冻结 `P0-01 ~ P0-04`，`P1-01` 继续保持 freeze-late
- 当前阶段主要输出仍是文档与 contract，不是实现代码

## 5. Risks

- 如果不先冻结 typed scope contract，后续 `web / api / worker` 会出现三套不同语义
- 如果 evidence grouping 没有独立事实源，前端很容易退化为解析 answer 文本
- 如果 retrieval explain 与 answer 页面各自讲一套故事，`Phase 2B` 的解释力会反向伤害信任
- 如果现在就急着拆实现 lane，会把 still-open contract 问题转成并行冲突

## 6. Plan

1. 审计当前 `Phase 2A` 已实现代码与 `Phase 2B` 产品目标之间的差距。
2. 冻结 `scope / history / retrieval / evidence / ops` 的技术方向，产出 `architecture / data-model / api` 评估稿。
3. 给出进入 contract freeze 的前置结论与不再重开的排除项。
4. 基于 freeze 文档决定是否进入 implementation freeze 和 lane 拆分。

## 7. Validation

- 单元测试：不适用，当前为文档与 contract 评估
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：执行 `git diff --check` 与 `pnpm docs:check`，并保留最近稳定 main CI 作为工程基线

## 8. Rollback

- 若技术评估结论被证明不成立，可保留本批技术文档作为分支决策记录，但不得在未更新 handoff/status 的情况下直接拆实现 lane

## 9. Decision Log

- `2026-04-12`: 代码审计确认 `Phase 2A` 的 `answers / retrieval / evidence / ops` 主链已真实存在，`Phase 2B` 应采用增量增强路线
- `2026-04-12`: 已完成 `Phase 2B` 第一轮 `architecture / data-model / api / prerequisites` 技术评估，当前无新的硬 blocker，可进入正式 contract freeze
