# Phase 3A Planning And Scope

## 1. Metadata

- `plan_id`: `phase-3a-planning-and-scope`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md), [Phase 3A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md), [v7 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md), [Phase 3A tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-p0-technical-tradeoffs.md), [v6 handoff](/Users/coderlauu/xRag/docs/handoff/v6.md), [v6 status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)

## 2. Objective

在 `Phase 2C` 已完成的治理主板基线上，为 `v7 / Phase 3A` 形成清晰的问题定义、优先级和版本边界，并完成是否进入 `technical-evaluation` 的判断。

## 3. Scope

### In Scope

- 归档 `v6 / Phase 2C`，把当前入口切换到 `v7 / Phase 3A`
- 基于 `Phase 2C` 的 `P1-01 / P1-02` 与实际运行态痛点，收敛下一轮版本目标
- 明确 `must-have / optional / deferred`，形成新的版本边界
- 规划并补齐后续需要的 `PRD / backlog / interaction delta / technical tradeoff` 资产
- 定义进入技术评估前的最小 gate

### Out Of Scope

- 直接开始 `Phase 3A` 代码开发
- 在没有技术评估与 contract freeze 前修改 `schema / shared-types / API contract`
- 把自动 remediation、团队协作或多模型治理默认提升为 `P0`
- 重写 `Phase 2A / 2B / 2C` 已完成的主问答链路

## 4. Assumptions

- `v6 / Phase 2C` 已完成并归档，closeout main CI 基线为 run `24543526168`
- 下一轮版本优先从 `Phase 2C` 已明确的 `P1` 补强项中选 scope，不默认提升更大的 deferred 能力
- 当前阶段主要输出是文档与决策，不是生产代码

## 5. Risks

- 若直接把 `drill-down`、`cost-quality`、`runbook 联动` 和自动 remediation 混在一起规划，`v7` 很容易再次出现范围膨胀
- 若没有先冻结问题定义，就进入技术评估，后续 contract freeze 极易返工
- 如果 `current.md`、`AGENTS.md`、`status` 与 `handoff` 不同步，后续 resume 会误读当前活跃版本

## 6. Plan

1. 归档 `v6 / Phase 2C`，切换 `current.md`、`handoff`、`status` 与 `AGENTS.md` 的当前入口。
2. 基于 `Phase 2C PRD / backlog / interaction / tradeoff` 提炼 `Phase 3A` 候选范围与排序原则。
3. 形成 `Phase 3A` 的 `PRD / backlog / interaction delta / technical tradeoff` 第一版。
4. 根据边界稳定度决定是否切换到 `technical-evaluation`。

## 7. Validation

- 单元测试：不适用，当前为文档与规划变更
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：不适用，当前不改运行时代码
- 文档阶段至少保证 `git diff --check` 与 `pnpm docs:check` 通过

## 8. Rollback

- 若 `v7 / Phase 3A` 的问题定义被证明不成立，可把 `current.md` 重新切回 latest completed `v6 / Phase 2C`，并保留本次新建规划资产但不作为当前入口

## 9. Decision Log

- `2026-04-17`: 将“启动下一版本规划”归类为 `new-version`
- `2026-04-17`: 依据 `Phase 2C` 已完成事实，创建 `v7 / Phase 3A` 的 `handoff / status / PRD / backlog / interaction delta / technical tradeoff / active exec plan`
- `2026-04-17`: `pnpm docs:check` 与 `git diff --check` 已通过，`v7` 版本规划第一版可作为后续技术评估入口
- `2026-04-17`: `planning-and-scope` 完成，active exec plan 已切换到 `technical-evaluation`
