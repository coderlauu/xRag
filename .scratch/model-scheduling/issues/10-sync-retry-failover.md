# 10 — 同步重试与故障转移

**What to build:** 把 RoutePlan、Deadline/错误动作和 breaker 接入同步 Chat tracer，实现有界原地重试与候选切换。

**Blocked by:** 无（06、07、08、09 已完成）

**Status:** done（2026-08-03）

## Slice

- 按 RoutePlan 顺序申请许可并执行；permission reject、等待、retry、switch 和预算终止都记录 route event。
- 同 deployment 重试、跨 deployment 切换和所有恢复动作共享总 deadline。
- 429 只按已冻结 quota-domain/Retry-After 动作执行，不在本工单重新解释策略。
- 最终成功保留中间失败；耗尽时失败异常包含 logical call 与完整稳定执行摘要。
- 每个 HTTP attempt 保存 failure phase，非 HTTP 决策不伪装成 attempt。

## Acceptance

- [x] SYN-02~SYN-17 的执行集成项自动化。
- [x] SYN-18、SYN-20、SYN-26、SYN-28 自动化：缺 Retry-After、调用前非法参数、Provider 200 畸形响应和有限 body 中途 reset 均按冻结动作执行。
- [x] DIA-03 自动化：中间失败后最终成功时，调用级 outcome 成功且失败 attempt 仍完整持久化。
- [x] RTE-11 / DIA-19 自动化：HALF_OPEN 许可被抢后切换次选，持久化有序 `BREAKER_PERMISSION_REJECTED` / `FAILOVER_SELECTED`，且不伪造 HTTP attempt。
- [x] DIA-20 自动化：retry wait 到预算耗尽的 route-event 顺序真实，完成态保存稳定 error/phase。
- [x] 首选 503 → 次选成功时结果与诊断均标记 failover，中间失败仍可见。
- [x] permission reject 与预算不足均 attempt=0 且 route event 可解释。
- [x] Provider 调用期间无活动数据库事务。
- [x] `./mvnw -B verify` 通过（281 tests，0 failures，2 skipped）。

## Handoff

同步 Chat 已接通 RoutePlan、共享 monotonic Deadline、AttemptBudget、breaker permission、分类结算与完成态诊断。工单 14 可复用同一恢复边界实现 Embedding work unit/mixed execution；工单 16 可复用同步候选恢复语义实现 Rerank/Noop。

**Design:** [architecture §8](../../../tech/model-scheduling/architecture.md#8-同步执行骨架)
