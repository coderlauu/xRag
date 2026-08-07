# 16 — Rerank 与显式 Noop

**What to build:** 实现稳定 candidate ID 的 Rerank 端口、Provider adapter 与显式 Noop 降级，并复用已验收的同步恢复骨架。

**Blocked by:** 无（10 已于 2026-08-03 完成）

**Status:** done（2026-08-07）

## Slice

- 定义 RerankCommand/Result，调用前校验 ID、top-N 和 Provider limits。
- Provider index 映射回稳定 candidate ID。
- 可恢复错误使用共享路由/breaker/failover；配置/取消/非法请求不允许 Noop 掩盖。
- Noop 不产生 Provider attempt、不写 score、不改变 breaker。
- 结果与诊断一致标记 `rerankApplied=false` 和稳定 `RerankDegradationReason`，分开“目录未声明能力”、“约束下无候选”与“可恢复候选耗尽”。

## Acceptance

- [x] RRK-01~RRK-12 自动化（14 例，含两条额外的隐私与边界断言）。
- [x] DIA-05：Noop 持久化为 `DEGRADED`、`rerankApplied=false`、非空 `degradedReason`，`executionSummary` 的 actual 三字段为 null、contributor 为 0，不伪造成功 attempt。
- [x] query/candidate text 不进入诊断：`CompletedCallDiagnostic` 里只有候选数与 topN 两个计数。
- [x] Provider 无 Rerank 能力时不以 Embedding 分数伪装：`RerankResult` 在构造期拒绝"降级却带 score"。
- [x] Noop 的结果、route event 与持久化事实一致（`NOOP_DEGRADED` 终止事件 + `DEGRADED` outcome + 原顺序 top-N）。
- [x] `./scripts/ci-validate.sh` 通过（clean 构建后 backend 375 tests，0 failures，2 skipped）。

**Design:** [architecture §11](../../../tech/model-scheduling/architecture.md#11-rerank-与-noop)

## 实现记录（2026-08-07）

新增公共契约 `RerankClient` / `RerankCommand` / `RankedCandidate` / `RerankResult`（`com.app.ai.model`），
路由客户端 `com.app.ai.rerank.RoutedRerankClient`，适配器 `OpenAiCompatibleRerankProvider`，
装配 `RerankClientConfiguration`。

- **恢复循环没有抽公共类，与 `RoutingChatClient` / `RoutedEmbeddingClient` 同形自持。**
  这是仓库既有取舍：三个 routed client 共享的是 `RoutePlanner` / `AttemptBudget` /
  `MonotonicDeadline` / `FailureActionPolicy` / breaker registry 这些运行时原语，而不是一个共享循环类。
  硬抽公共骨架要把 Chat 已验收的行为一起动，风险不划算。
- **不变量放进 record 构造期。** `rerankApplied == (degradationReason == null)` 与"降级不得带 score"
  由 `RerankResult` 强制，调用方因此永远不用猜"没有 reason 是不是就代表成功"。
- **语义校验放在 client 而不是 `RerankCommand` 构造期。** ID 唯一与 topN 范围若在构造期抛
  `IllegalArgumentException`，就没有 requestId、没有诊断记录；放在 client 里才能和其他失败一样
  产出 `INVALID_REQUEST` 的 `ModelCallException` 加一条完整诊断。
- **文档上限走路由过滤而不是单独的前置拒绝。** 候选数超过某个 deployment 的 `maxDocuments` 时，
  该 deployment 以 `CALLER_CONSTRAINT_MISMATCH` 被过滤并记进诊断；没有候选剩下就是
  `NO_ELIGIBLE_CANDIDATE`。这样 RRK-11 的"不静默"和 RRK-12 的"调用方约束下无合格候选"共用一套机制，
  也保留了"A 放不下但 B 放得下就用 B"的正确行为。截断候选被明确排除——那正是矩阵要防的静默。
- **缺 secret 的判定简化为"存在声明 RERANK 且 enabled 的 deployment，其 Provider 未配置"。**
  规格说的是"本应匹配"，严格判定需要重跑一遍除配置外的全部过滤条件；由于只有在**完全没有合格候选**时
  才走到这个分支，按上述简化直接失败是更安全也更可诊断的一侧。

`ai-models.yml` 新增 `aliyun-qwen3-rerank-cn` 但 **`enabled: false`**：打开它必须与注入
`DASHSCOPE_API_KEY` 同时做，只开一半会按上一条直接以 `AUTHENTICATION` 失败。`max-documents: 100`
是占位值，待工单 23 按当天官方文档核实。

**遗留：** `OpenAiCompatibleRerankProvider` 走的是 Cohere/Jina/百炼共用的
`{query, documents, top_n}` → `{results:[{index, relevance_score}]}` 形状，**未经真实线路验证**；
路径、字段名与是否需要 `return_documents` 都要在工单 23 核实。适配器本身只有编解码，
没有独立的 HTTP 测试——它的行为边界目前由 `RoutedRerankClient` 的假 Provider 用例覆盖。
