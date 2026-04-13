# Phase 2B Planning And Design

## 1. Metadata

- `plan_id`: `phase-2b-planning-and-design`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v5 handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md), [Phase 2B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md), [v5 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md), [Phase 2B P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md), [v4 handoff](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md), [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)

## 2. Objective

在 Phase 2A 可信问答闭环完成后，定义并冻结下一轮范围、验收与实施顺序，启动更完整的问答体验与检索可观测性规划。

## 3. Scope

### In Scope

- 归档 `v4 / Phase 2A` 并把当前入口切换到 `v5 / Phase 2B`
- 基于 `Phase 2A` 的 `P1` 与 `Phase 2B+` 候选项收敛下一轮版本目标
- 明确 `must-have / optional / deferred`，形成新的版本边界
- 规划并补齐后续需要的 `PRD / backlog / interaction / technical tradeoff` 资产
- 定义进入技术评估和实现 freeze 前的最小 gate

### Out Of Scope

- 直接开始 `Phase 2B` 代码开发
- 重写 `Phase 2A` 已上线的问答主链
- 开放互联网联网回答、浏览器插件、团队协作、移动端
- 在没有产品边界冻结前提前修改 `schema / shared-types / API contract`

## 4. Assumptions

- `Phase 2A` 的稳定发布基线为 `db3b620`，latest closeout docs 基线为 `66767af`
- 下一轮版本优先从 `Phase 2A` 已定义的 `P1` 补强项中选 scope，不默认提升 `Phase 2B+` 的 deferred 能力
- 当前阶段主要输出是文档与决策，不是生产代码

## 5. Risks

- 若直接把 `P1` 与 `Phase 2B+` 混在一起规划，容易再次出现范围膨胀
- `question history`、`evidence package`、`retrieval workbench` 涉及多页与多模块联动，若没有统一优先级会导致实现 lane 边界失真
- 如果 `current.md`、`AGENTS.md`、版本状态和归档状态不同步，后续 resume 会直接误读当前版本

## 6. Plan

1. 归档 `v4 / Phase 2A`，切换 `current.md`、`handoff`、`status` 与 `AGENTS.md` 的当前入口。
2. 基于 `Phase 2A PRD / backlog / interaction / evaluation` 提炼 `Phase 2B` 候选范围与排序原则。
3. 明确下一轮需要新增的产品与技术文档清单，以及进入技术评估前必须先回答的问题。

## 7. Validation

- 单元测试：不适用，当前为文档与规划变更
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：执行 `git diff --check` 与 `pnpm docs:check`，并保留最近稳定 main CI 作为工程基线

## 8. Rollback

- 若 `v5 / Phase 2B` 的范围判断被证明错误，可把 `current.md` 重新切回 `v4 / Phase 2A`，并将本次新建规划资产保留但不作为当前入口

## 9. Decision Log

- `2026-04-11`: 依据 `xrag-iteration-governor` 将本次需求分类为 `new-version`，因为当前推进目标已从完成的 `v4 / Phase 2A` 切换到下一轮产品范围规划
- `2026-04-11`: 已补齐 `Phase 2B` 的 `PRD / backlog / interaction delta / technical tradeoff` 第一版，并冻结 `P0-01 ~ P0-04` 为本轮主链、`P1-01` 为次级增强
- `2026-04-12`: 规划与设计阶段完成，活跃 exec plan 切换到 [Phase 2B Technical Evaluation And Contract Freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-12-phase-2b-technical-evaluation-and-contract-freeze.md)
