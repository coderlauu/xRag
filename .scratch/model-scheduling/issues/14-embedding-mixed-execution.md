# 14 — Embedding 批次与 mixed execution

**What to build:** 实现共享调度层的 Embedding 能力、批次 work unit 与等价 deployment 恢复，并准确表达一次逻辑调用由多个 deployment 共同贡献。

**Blocked by:** 03, 04, 07, 08, 09, 10

**Status:** done

## Slice

- 定义内部 Embedding 能力命令/结果和 Provider adapter，固定 ModelIdentity。
- 按所有合格等价 deployment 的安全限制拆 batch；每批独立 attempt 预算，全调用共享 deadline。
- 保序恢复 Provider index，拒绝重复/越界/缺失、维度不符和非有限浮点。
- 中间 batch 可切换等价 deployment，已完成 batch 不重算。
- 进程内 `EmbeddingResult` 保留 ordered per-batch contributing execution；持久化以成功 attempt 的 `work_unit_index` 为事实源，call 只写 mixed/count 摘要。

## Acceptance

- [x] EMB-02~EMB-12 自动化。
- [x] EMB-17~EMB-19、EMB-23~EMB-24 自动化：mixed/single contributor 摘要与 attempts 一致，缺少等价证据的 legacy identity 不能形成多 deployment 候选；部分批失败或取消不返回 vectors，也不把此前成功批误算成调用级贡献者。
- [x] 不同 batch 由 A/B deployment 完成时，向量顺序正确；成功 attempts 可派生相同的贡献视图和 mixed/count 摘要。
- [x] 备选 deployment 批量上限较小时不会收到超限请求（切分取所有合格候选 `maxBatchItems` 的最小值）。
- [x] 同维不同 identity 永不互切。
- [x] Provider 调用期间无活动数据库事务。

**Design:** [architecture §10](../../../tech/model-scheduling/architecture.md#10-embedding-迁移)

## Notes

- 范围只到 `com.app.ai`：新增 `com.app.ai.model` 的 `EmbeddingClient / EmbeddingCommand /
  EmbeddingResult / EmbeddingExecutionInfo / EmbeddingBatchExecution` 与
  `com.app.ai.embedding.RoutedEmbeddingClient`。`com.app.knowledge.embedding.*` 未改动，
  Phase 1 调用点迁移与 EMB-01、13~16、20~22 留给工单 15。
- **批次间候选起点采用 sticky**：每个 work unit 从上一批成功贡献的候选序号开始，而不是每批
  退回首选。设计文档未规定这一点；理由是 70 批场景下"每批退回首选"会在每一批重付一次失败
  代价，且 failover 语义不应来回抖动。
- 空 `texts` 返回空结果、不创建 Provider attempt，但仍记录一条 `SUCCEEDED` 诊断
  （0 attempts、contributor count=0），保持每次逻辑调用可追溯；DDL 的
  `ck_ai_call_execution_summary` 与 `ck_ai_call_counts` 都允许该组合。
- 复用而非重写的部分：`RoutePlanner` 已按 identity 过滤并按最小 `maxBatchItems` 切 work
  unit；`ModelCatalogFactory.validateEmbeddingIdentities` 已强制 legacy identity 单绑定；
  `CompletedCallDiagnostic.executionSummary()` 已从成功 attempt 派生 mixed/count 摘要，
  非成功终态直接归零。
- 已知重复：候选循环（breaker 许可 → attempt 预算 → 重试/切换 → route event）与
  `RoutingChatClient` 结构同形但未抽取公共实现。留到工单 16（Rerank）出现第三个调用点时
  一并设计抽象，避免只凭两个样本猜接口。
