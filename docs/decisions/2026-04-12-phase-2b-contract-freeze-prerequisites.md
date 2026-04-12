# Phase 2B Contract Freeze Prerequisites

**日期：** 2026-04-12
**版本：** `v5 / Phase 2B`
**状态：** ready-for-contract-freeze
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 2B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md)
- [Phase 2B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md)
- [Phase 2B API Design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md)
- [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 1. 本文档目的

这份文档不直接冻结 contract，而是回答一个更基础的问题：

`Phase 2B` 是否已经具备进入正式 contract freeze 的现实前提？

当前判断重点只覆盖：

1. `scope`
2. `history / continue asking`
3. `retrieval explain`
4. `evidence grouping`

`ops` 仍保留为 `freeze-late` 的次级项。

---

## 2. 验证范围

本次判断基于以下事实面：

- 当前 `v5 / Phase 2B` 的 `PRD / backlog / interaction delta / technical tradeoff`
- 当前 repo main 上已经存在的：
  - `answers` API
  - `answer_sessions`
  - `retrieval_runs / retrieval_run_hits`
  - `answer_citations`
  - web ask/search/ops 页面
  - worker retrieval / synthesis / citation pipeline
- `Phase 2A` 已冻结的：
  - runtime contracts
  - schema / shared-types / API / 状态机约束

---

## 3. 结论总览

| 前置条件 | 当前结论 | 状态 |
| --- | --- | --- |
| `Phase 2A` 代码基线是否真实存在且可承接增量 | 已确认现有 repo 已实现 `answers / retrieval / evidence / ops` 主链，无需重做 | ready-for-freeze |
| 扩展 scope 的方向是否收敛 | 已收敛为“保持 `scope.mode` 稳定，新增 typed filters，保留 `search_result` snapshot” | ready-for-freeze |
| history / continue asking 是否有最小模型 | 已收敛为“新 session + `continued_from_session_id` + scope-only 继承” | ready-for-freeze |
| retrieval explain 与 evidence 是否能共用一条事实链 | 已收敛为“复用 `retrieval_runs / retrieval_run_hits / answer_citations`，补 claim 级事实源” | ready-for-freeze |
| `ops` 是否会阻塞 `P0` freeze | 不阻塞；保留到 `P1-01` 再扩字段与聚合 | freeze-late |

结论：

- `Phase 2B / P0-01 ~ P0-04` 已无新的硬 blocker。
- 当前可以进入正式 contract freeze。
- `ops` 继续作为非阻断项，不应反向拖慢 `P0` 主链。

---

## 4. 进入 freeze 时必须一起锁定的面

`Phase 2B` 下一步不能只改其中一层，必须一起冻结：

1. `schema`
   - `answer_sessions`
   - `answer_claims`
   - `answer_citations`
   - `retrieval_run_hits`
2. `shared-types`
   - `AnswerScope`
   - `CreateAnswerRequest`
   - `AnswerSessionResponse`
   - `AnswerRetrievalTraceResponse`
   - history / evidence / exclusion reason 新类型
3. `API / OpenAPI / SDK`
4. `web`
   - ask page
   - answer detail
   - history list
   - retrieval workbench
5. `worker`
   - scope filter resolution
   - claim-group 写入
   - retrieval exclusion reason 归一化

---

## 5. freeze 前还要明确写死的问题

这些问题已经不再是“方向选择”，而是下一份 freeze 文档里必须明确写死的 contract：

1. `ScopeFilterSet` 的字段名、数组上限、tag 组合语义、`date_from / date_to` 的闭区间规则
2. `continued_from_session_id` 的校验规则，以及 continue asking 是否只允许继承 scope
3. `answer_claims` 与 `evidence_groups` 的最终字段形状
4. `RetrievalExclusionReason` 的正式枚举值
5. `GET /api/v1/answers` 的分页 contract 与返回最小字段集
6. 对旧 session 缺少 claim 数据时的向后兼容规则
7. 若 `P1-01` 启动，`ops` trends 的窗口与口径如何与 eval contract 保持同源

---

## 6. 明确不应在 freeze 阶段重新打开的方向

当前不应在 freeze 阶段重新讨论：

- 是否引入 conversation threads
- 是否做 memory assistant
- 是否新增 explain rerun pipeline
- 是否把 `scope.mode` 扩成多个新枚举
- 是否为 `ops` 再造新的 metrics store

这些方向在本轮技术评估里已经被排除。

---

## 7. 下一步建议

1. 以 [Phase 2B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md)、[Phase 2B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md)、[Phase 2B API Design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md) 为基础，产出正式 contract freeze 文档。
2. contract freeze 完成前，不要把 `web / worker / test` 拆成并行实现 lane。
3. contract freeze 完成后，再决定是否需要单独的 implementation-freeze prerequisites 文档与 lane 切分。
