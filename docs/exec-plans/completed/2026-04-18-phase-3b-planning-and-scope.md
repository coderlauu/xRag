# Phase 3B Planning And Scope

## 1. Metadata

- `plan_id`: `phase-3b-planning-and-scope`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md), [Phase 3B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md), [v8 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md), [Phase 3B tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md), [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md)

## 2. Objective

启动 `v8 / Phase 3B`，把 v7 的只读诊断 workflow 推进到 human-in-the-loop ops recovery 的规划边界：从诊断样本生成恢复候选、人工确认 rerun、执行审计与 rollback plan，同时明确不进入全自动 remediation。

当前计划已完成并归档；后续 technical evaluation、contract freeze、implementation freeze 与 [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md) 也已完成，当前进入 [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-release-readiness.md)。

## 3. Scope

### In Scope

- 使用 `xrag-iteration-governor` 将本需求分流为 `new-version`
- 创建并维护 `docs/handoff/v8.md`、`docs/status/v8-phase-3b.md` 和本 active exec plan
- 更新 `docs/handoff/current.md` 与 `AGENTS.md`，把默认恢复入口切到 v8
- 将 `v7 / Phase 3A` 标记为归档基线，不继续扩大 Phase 3A scope
- 明确 `v8 / P0` 候选：
  - `P0-01 Recovery Candidates`
  - `P0-02 Operator-approved Rerun`
  - `P0-03 Recovery Execution Audit`
  - `P0-04 Guarded Rollback Planning`
- 形成后续 product docs 的写作范围：PRD、backlog、interaction delta、technical tradeoffs、contract freeze prerequisites
- 决定 production 历史 `not_indexed` 治理是否保留为版本外 runbook，或在 dry-run 之后进入 v8 scope
- 定义进入实现前的 gate：`contract-guard`、必要时 `answer-quality-gate`、implementation freeze 和 validation matrix

### Out Of Scope

- 编写业务代码
- 修改 schema、shared-types、DTO、OpenAPI、API client、状态枚举或 API path
- 自动 remediation、自动 rerun、自动 rollback
- 多模型治理、团队协作权限、浏览器插件、移动端、开放互联网联网回答
- 把未验证的 production recovery 需求直接写入 P0 实现范围

## 4. Assumptions

- `v7 / Phase 3A` 已完成 `Lane 0 / Lane 0G / Lane A / Lane B / Lane C / Lane D` 与 release-readiness。
- `/ops` 已具备 diagnostic samples、answer replay、document replay、deployment compare 这些 read model 入口。
- Recovery 不能依赖前端自行推断事实；候选、计划、执行和审计必须来自服务端事实。
- 新增 operator action 大概率会触碰 API/schema/shared-types/状态机，需要先冻结 contract。
- 任何 answer/retrieval/citation/freshness/refusal 相关变化都可能影响 Phase 2A/2B 信任边界，需要 answer quality gate。

## 5. Risks

- 把 recovery candidate 做成隐式自动 remediation，会绕过人工确认和审计边界。
- 把 rollback planning 做成自动 rollback，会显著扩大生产风险。
- 新增长任务或 queue job 若缺少终态收口，会重演 Ask active-session stuck polling 问题。
- 未先 dry-run production `not_indexed` 数据就并入 P0，可能把一次性运维事项误做成产品功能。
- 多模型治理和协作权限都属于独立大版本能力，混入 v8 会造成范围膨胀。

## 6. Plan

1. 完成版本启动资产：
   - `status`: `completed`
   - 输出：v8 handoff、v8 status、本 active exec plan、current handoff 更新、AGENTS 更新
2. 完成 planning-and-scope：
   - `status`: `completed`
   - 输出：Phase 3B PRD、backlog、interaction delta、P0 technical tradeoffs
3. 完成 technical evaluation：
   - `status`: `pending`
   - 输出：architecture、data model、API design、contract freeze prerequisites
4. 完成 contract freeze：
   - `status`: `pending`
   - 输出：contract freeze、OpenAPI/API client/schema/shared-types 影响评估；必要时 answer-quality gate 记录
5. 完成 implementation freeze：
   - `status`: `pending`
   - 输出：lane 拆分、写入边界、测试矩阵、rollback plan
6. 只有在 contract/source-of-truth 冻结后，才进入 implementation lanes。

## 7. Validation

- 文档一致性：`pnpm docs:check`
- Contract 漂移检查：planning 阶段不运行；若后续 contract 资产生成或修改，运行 `pnpm contract:check`
- 代码格式空白：`git diff --check`
- 单元测试：planning 阶段不需要；进入实现后按 lane 补充
- 集成测试：planning 阶段不需要；进入 API/worker mutation 后必须覆盖
- E2E / smoke：planning 阶段不需要；进入 `/ops` recovery UI 后必须覆盖 operator-approved workflow

## 8. Rollback

- 如果 v8 scope 被撤回，在没有业务代码变更前可删除 v8 handoff/status/active exec plan，并把 `docs/handoff/current.md` 与 `AGENTS.md` 恢复到 v7 completed baseline。
- 如果后续 product docs 已创建但未进入实现，可通过文档 revert 保留 v7 归档基线不变。
- 一旦进入 contract freeze 或实现 lane，回滚必须同时处理 API/schema/shared-types/OpenAPI/API client 与 migration 影响。

## 9. Decision Log

- `2026-04-18`: 根据 `xrag-iteration-governor` artifact matrix，将 v7 后续诊断产品化判断为 `new-version`，启动 `v8 / Phase 3B`。
- `2026-04-18`: v8 第一阶段限定为 planning-and-scope；自动 remediation、自动 rerun、自动 rollback 不进入 P0。
- `2026-04-18`: v8 版本启动资产已创建并通过 `pnpm docs:check` 与 `git diff --check`。
- `2026-04-18`: 已创建 Phase 3B PRD、backlog、interaction delta 与 P0 technical tradeoffs；planning-and-scope 完成，当前入口切换到 technical-evaluation。
- `2026-04-18`: 归档前再次通过 `pnpm docs:check` 与 `git diff --check`。
