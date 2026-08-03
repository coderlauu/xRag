# 09 — Breaker 注册表、原子许可与转换

**What to build:** 引入 Resilience4j 手工许可 API，实现进程内 breaker registry、原子 HALF_OPEN 许可、分类结算与转换持久化；不实现候选 failover。

**Blocked by:** 无（01、04、08 已完成）

**Status:** done

## Slice

- registry key=`provider/deployment/capability`，参数使用已批准配置。
- attempt 前 `tryAcquirePermission`；许可拒绝不产生 attempt，但写有时间戳的 route event。
- 错误动作决定 `onSuccess/onError/releasePermission`，取消、400/401/403/429 按契约处理。
- transition 异步持久化且不含错误正文；registry 重建语义可读取。
- 本工单只验证一个 breaker key，不实现 RoutePlan 遍历或同步/流式切换。

## Acceptance

- [x] CBR-01~CBR-14 自动化。
- [x] CBR-15 breaker 侧自动化：Provider 协议失败计入普通失败率；调用方非法输入在 attempt/permission 前拒绝。
- [x] HALF_OPEN 并发仅放行配置许可数；拒绝结果带真实发生时间且不产生 attempt。
- [x] CHAT OPEN 不影响同 deployment EMBEDDING。
- [x] 取消与终态竞态下每个许可只结算一次。
- [x] transition 通过异步 writer 独立持久化，只含稳定原因，写入失败不改变 breaker 状态。
- [x] `./mvnw -B verify` 通过（256 tests，0 failures，2 skipped）。

## Handoff

RTE-11 / DIA-19 要求 RoutePlan 首选 HALF_OPEN 许可被抢后切到次选，属于工单 10 的同步 failover 执行路径。本工单已提供带发生时间的 `BreakerPermissionRejected`；工单 10 负责在逻辑调用内分配 sequence/monotonic offset，持久化真实 `BREAKER_PERMISSION_REJECTED` 与 `FAILOVER_SELECTED` route events，并验证不伪造 HTTP attempt。

**Design:** [architecture §7](../../../tech/model-scheduling/architecture.md#7-熔断器)
