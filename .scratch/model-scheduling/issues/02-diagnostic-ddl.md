# 02 — 诊断纯 DDL

**What to build:** 只新增 `V6__model_scheduling_diagnostics.sql`，把逻辑调用、候选、route event、真实 HTTP attempt 与 breaker transition 的 canonical Schema 固定下来。

**Blocked by:** 无

**Status:** done（2026-08-02）

## Slice

- 按 data-model 创建诊断表、索引、外键、check 和 cascade。
- `ai_route_event` 保存过滤后执行期决策，包括 breaker permission reject、预算拒绝、等待、重试、切换与终止。
- logical call/attempt 保存稳定 `failure_phase` 或等价终止阶段，能区分 connect、headers、first event、first meaningful、idle、total 等失败位置。
- `ai_attempt.work_unit_index` 与成功 attempt 是 Embedding batch 贡献的事实源；`ai_logical_call` 只保存 `mixed_execution` 和 `contributing_deployment_count` 摘要，不新增 contribution 表。
- 本工单只包含 migration 与 Schema 测试，不实现 Java DTO、writer、清理任务或 API。

## Acceptance

- [x] V6 在空库和既有 Phase 1 Schema 上均可一次迁移成功。
- [x] 表、列、索引、约束和级联行为与 data-model 完全一致。
- [x] route event 顺序唯一且稳定；failure phase、finish reason 和 Rerank degradation reason 使用受约束的稳定值；stream/capability、Rerank 结果交叉字段不能互相矛盾；mixed execution 摘要可由成功 attempts 校验。
- [x] migration 不修改或回填 Phase 1 业务表。
- [x] DIA-01 自动化通过：五张表、索引、check、FK 和 cascade 与 canonical DDL 一致。

**Design:** [data model §4](../../../tech/model-scheduling/data-model.md#4-ddl)
