# 08 — Deadline、attempt 预算与错误动作

**What to build:** 冻结并实现统一 Deadline、work-unit attempt 预算和唯一错误动作表；不在本工单接入多候选执行器。

**Blocked by:** 无（01 已于 2026-08-02 完成）

**Status:** done（2026-08-03）

## Slice

- `Deadline` 使用单调时钟，调用方 timeout 不能放宽模块上限。
- 计算每个传输阶段的 timeout，取“剩余总预算”和阶段上限的较小值；预算不足时禁止开始 attempt。
- 独立计算 logical call、work unit、同 deployment attempt，避免 Embedding 多批被全局三次误伤。
- 唯一错误动作表区分 caller/config contract 与 Provider protocol violation，冻结 retry/switch/breaker/noop 资格。
- 规范 Retry-After、受限 jitter、取消/中断传播，以及 connect/headers/first-event/first-meaningful/idle/total failure phase。

## Acceptance

- [x] RTE-14~RTE-16、SYN-05~SYN-12、SYN-14~SYN-15 的纯策略测试自动化。
- [x] RTE-18~RTE-19 与 SYN-19 自动化：monotonic Deadline、连续恢复动作共享预算、全部 AttemptFailure 唯一映射。
- [x] 架构、API 和测试使用同一错误枚举与动作表，不存在相反恢复结论。
- [x] 剩余 1 秒时计算出的任何阶段 timeout 都不超过 1 秒；实际传输遵守该值由后续能力 tracer/Provider 工单验证。
- [x] Clock/Jitter/Sleeper 可注入，测试不真实等待。
- [x] 本工单不实现 RoutePlan、breaker 或 failover loop。

**Design:** [architecture §6.2~6.3](../../../tech/model-scheduling/architecture.md#62-一个预算覆盖全部恢复动作)
