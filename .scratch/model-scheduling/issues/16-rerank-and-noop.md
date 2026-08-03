# 16 — Rerank 与显式 Noop

**What to build:** 实现稳定 candidate ID 的 Rerank 端口、Provider adapter 与显式 Noop 降级，并复用已验收的同步恢复骨架。

**Blocked by:** 10

**Status:** blocked

## Slice

- 定义 RerankCommand/Result，调用前校验 ID、top-N 和 Provider limits。
- Provider index 映射回稳定 candidate ID。
- 可恢复错误使用共享路由/breaker/failover；配置/取消/非法请求不允许 Noop 掩盖。
- Noop 不产生 Provider attempt、不写 score、不改变 breaker。
- 结果与诊断一致标记 `rerankApplied=false` 和稳定 `RerankDegradationReason`，分开“目录未声明能力”、“约束下无候选”与“可恢复候选耗尽”。

## Acceptance

- [ ] RRK-01~RRK-12 自动化。
- [ ] DIA-05 自动化：Noop 持久化为 `DEGRADED`、`rerankApplied=false`，且不伪造成功 attempt。
- [ ] query/candidate text 不进入诊断。
- [ ] Provider 无 Rerank 能力时不以 Embedding 分数伪装。
- [ ] Noop 的结果、route event 与持久化事实一致。
- [ ] `./mvnw -B verify` 通过。

**Design:** [architecture §11](../../../tech/model-scheduling/architecture.md#11-rerank-与-noop)
