# Current Handoff

当前默认入口始终指向“当前正在推进的版本”；若下一版本尚未启动，则指向最新完成版本。

- 当前有效版本：[v6 / Phase 2C](/Users/coderlauu/xRag/docs/handoff/v6.md)
- 当前版本状态：[v6 / Phase 2C Status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)

上一阶段归档：

- [v5 / Phase 2B Status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md)
- [v5 Handoff](/Users/coderlauu/xRag/docs/handoff/v5.md)
- [v4 / Phase 2A Status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)
- [v4 Handoff](/Users/coderlauu/xRag/docs/handoff/v4.md)
- [v3 / Phase 1C Status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)
- [v3 Handoff](/Users/coderlauu/xRag/docs/handoff/v3.md)
- [Phase 1A Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-02-phase-1a-retrospective.md)
- [v1 / Phase 1A Status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
- [v1 Handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)
- [v2 / Phase 1B Status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)
- [v2 Handoff](/Users/coderlauu/xRag/docs/handoff/v2.md)

当前基线技术资产：

- [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
- [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
- [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
- [Phase 2A OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-2a-api.json)
- [Deploy README](/Users/coderlauu/xRag/deploy/README.md)
- [Production Inspection Guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- [Production Data Loss And Deploy Incident Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-09-production-data-loss-and-deploy-incident-retrospective.md)
- [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

当前活跃版本资产：

- [v6 Handoff](/Users/coderlauu/xRag/docs/handoff/v6.md)
- [v6 Status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)
- [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
- [Phase 2C Implementation Lanes Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-16-phase-2c-implementation-lanes.md)
- [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
- [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
- [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
- [Phase 2C Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md)
- [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
- [Phase 2C P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
- [Phase 2C Implementation Freeze Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-freeze.md)
- [Phase 2C Contract Freeze Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-contract-freeze.md)
- [v5 Handoff](/Users/coderlauu/xRag/docs/handoff/v5.md)
- [v5 Status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md)
- [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
- [Phase 2B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
- [v5 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
- [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)

---

## 1. 当前版本一句话目标

在 `Phase 2B` 已完成正式收口后，`v6 / Phase 2C` 已进入 implementation lanes；`Lane 0: Contract To Code`、`Lane A: API Read Model And Governance Aggregation` 与 `Lane B: Deployment And Evaluation Fact Ingestion` 已完成，下一步按计划推进 `Lane C / Lane D`，并继续保护 schema、shared-types、DTO、OpenAPI 和 API client contract。

---

## 2. 当前版本边界

### 必须实现

- 启动 `v6 / Phase 2C` 的版本级脚手架与恢复入口
- 明确本轮问题定义、目标用户和验收标准
- 冻结 `PRD / backlog / interaction delta / technical tradeoff` 第一版事实源
- 完成 `implementation-freeze`，并进入 `implementation-lanes`
- 已完成 `Lane 0` 的 `schema / migration / shared-types / DTO / OpenAPI / API client / web api adapter`
- 已完成 `Lane A` 的 `/ops/overview` 与 `/ops/trends` 真实聚合：`readiness` 基于 `documents`，`runtime quality` 基于 `answer_sessions / answer_citations`，`evaluation quality` 基于 `evaluation_runs`，`incident clusters` 复用既有 incident candidates，`release guard` 优先读取 `deployment_records`
- 已完成 `Lane B` 的 deployment/evaluation fact ingestion：CI smoke evidence 可通过 SSH tunnel + PostgreSQL `127.0.0.1:5432` 回环映射写入 `deployment_records`，并提供受控 `evaluation_runs` 写入脚本
- 下一步完成 `Lane C` 的 web ops board 和 lightweight notices，以及 `Lane D` 的 integration / e2e / smoke 收口

### 本轮次级目标

- 在 `Lane A / B / C / D` 中保持清晰写入边界，保证后续实现不漂移已冻结 contract

### 明确不做

- 绕过已冻结 contract 修改 schema、shared-types、DTO、OpenAPI 或 API client
- 未经显式 contract 变更流程直接修改 `schema / shared-types / API / citation / scope` 主边界
- 默认把目标环境的历史回补当成版本产品目标
- 开放互联网联网回答
- 浏览器插件
- 团队协作
- 移动端
- 全自动 agent 工作流

### 当前阶段依赖的既有基线

- `v5 / Phase 2B` 已完成正式收口，并在 `2026-04-16` 切入归档态
- latest main GitHub Actions run `24514690725` 已成功，当前 main 维持进入 `Lane A` 前的绿态
- `Phase 2A / 2B` 已建立可信问答的核心信任边界与 release-ready 工程基线
- production 已可访问，且具备 `db.xrag.coderlau.cn` 与 PostgreSQL 回环映射的排查入口
- `pnpm recovery:backfill-indexing` 仍可用于目标环境的历史 `not_indexed` 文档治理，但目前不自动并入 `v6` 产品范围

---

## 3. 建议阅读顺序

1. [v6 Handoff](/Users/coderlauu/xRag/docs/handoff/v6.md)
2. [v6 Status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)
3. [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
4. [Phase 2C Implementation Lanes Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-16-phase-2c-implementation-lanes.md)
5. [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
6. [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
7. [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
8. [Phase 2C Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md)
9. [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
10. [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
11. [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
12. [Phase 2C P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
13. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
14. [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)
15. [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 4. 执行规则

1. 当前有效版本为 `v6 / Phase 2C`，当前节点为 `implementation-lanes`
2. 当前规划必须以 `Phase 2A / 2B` 已完成的信任边界为前提：`citation / refusal / freshness / release-readiness` 不得后退
3. 复杂任务继续先写 `docs/exec-plans/active/*.md`，当前真实进度统一写入 `docs/status/v6-phase-2c.md`
4. 当前已完成 implementation freeze，并切到 `implementation-lanes`；`Lane 0` 已完成，下一步按 `Lane A / B / C / D` 边界推进
5. `v5 / Phase 2B` 已归档，`v4 / Phase 2A` 继续作为实现、回滚与生产排障的现实基线

---

## 5. 本文件用途

以后恢复开发时，先读这三份：

1. `docs/handoff/current.md`
2. 当前版本 `docs/status/*.md`
3. 当前活跃 `exec plan`

如果需要再深入，再看对应 `tech/*` 和 retrospective。
