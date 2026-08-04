# 14 — Embedding 批次与 mixed execution

**What to build:** 实现共享调度层的 Embedding 能力、批次 work unit 与等价 deployment 恢复，并准确表达一次逻辑调用由多个 deployment 共同贡献。

**Blocked by:** 03, 04, 07, 08, 09, 10

**Status:** ready

## Slice

- 定义内部 Embedding 能力命令/结果和 Provider adapter，固定 ModelIdentity。
- 按所有合格等价 deployment 的安全限制拆 batch；每批独立 attempt 预算，全调用共享 deadline。
- 保序恢复 Provider index，拒绝重复/越界/缺失、维度不符和非有限浮点。
- 中间 batch 可切换等价 deployment，已完成 batch 不重算。
- 进程内 `EmbeddingResult` 保留 ordered per-batch contributing execution；持久化以成功 attempt 的 `work_unit_index` 为事实源，call 只写 mixed/count 摘要。

## Acceptance

- [ ] EMB-02~EMB-12 自动化。
- [ ] EMB-17~EMB-19、EMB-23~EMB-24 自动化：mixed/single contributor 摘要与 attempts 一致，缺少等价证据的 legacy identity 不能形成多 deployment 候选；部分批失败或取消不返回 vectors，也不把此前成功批误算成调用级贡献者。
- [ ] 不同 batch 由 A/B deployment 完成时，向量顺序正确；成功 attempts 可派生相同的贡献视图和 mixed/count 摘要。
- [ ] 备选 deployment 批量上限较小时不会收到超限请求。
- [ ] 同维不同 identity 永不互切。
- [ ] Provider 调用期间无活动数据库事务。

**Design:** [architecture §10](../../../tech/model-scheduling/architecture.md#10-embedding-迁移)
