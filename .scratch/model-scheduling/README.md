# 工单：大模型调度引擎

本目录把 [Phase 2 分角色任务书](../../docs/exec-plans/active/2026-07-28-model-scheduling-implementation-lanes.md) 转成 23 张可独立验收的原子工单。Step C 已于 2026-08-02 获项目所有者确认；Step D 的工单 01、02、03、04、06、07、08、09、10、11 已完成，当前 05、12、14、16、19 为 `ready`。

## 下一前沿

工单 11 已完成 WebClient/SSE Provider adapter，并解锁单候选流状态机。当前可独立推进五张工单：

- [05 只读调度总览 API](issues/05-diagnostics-overview-api.md)
- [12 单候选流状态机与单次订阅](issues/12-single-subscription-stream-state.md)
- [14 Embedding 批次与 mixed execution](issues/14-embedding-mixed-execution.md)
- [16 Rerank 与显式 Noop](issues/16-rerank-and-noop.md)
- [19 诊断保留与分批清理](issues/19-diagnostic-retention.md)

五张工单都已满足显式依赖：05 只组装 Overview，12 在已完成的 SSE adapter 上建立公共单订阅状态机，14/16 复用已完成的同步恢复边界，19 只做诊断物理清理。

## 依赖图

```text
01 领域词汇/目录 ─┬─> 03 Provider seam/fake ─────> 11 WebClient/SSE ─> 12 单候选流 ─┐
                   ├─> 07 RoutePlan ───────────────────────────────────────────────┤
                   ├─> 08 Deadline/预算/错误 ─┬─> 09 Breaker 许可 ────────────────┤
                   │                          └─────────────────────────────────────┤
02 诊断纯 DDL ─────┴─> 04 诊断快照/writer ─────────────────────────────────────────┤
03 + 04 + 08 ───────────────────────────────────> 06 单候选同步 Chat ─────────────┤
                                                                                │
06 + 07 + 08 + 09 ─────────────────────────────> 10 同步重试/切换                 │
07 + 08 + 09 + 12 ─────────────────────────────> 13 流式路由/切换                 │
03 + 04 + 07 + 08 + 09 + 10 ──────────────────> 14 Embedding mixed execution     │
14 ─────────────────────────────────────────────> 15 Phase 1 Embedding 原子迁移   │
10 ─────────────────────────────────────────────> 16 Rerank/Noop                  │
01 + 04 + 09 ──────────────────────────────────> 05 Overview API                  │
04 + 10 + 13 + 14 + 15 + 16 ──────────────────> 17 Calls/detail API              │
05 + 17 ────────────────────────────────────────> 18 Trace Console                │
02 + 04 ────────────────────────────────────────> 19 Retention                     │
05 + 10 + 13 + 14 + 15 + 16 + 17 + 18 ────────> 20 Privacy audit                 │
04 + 05 + 10 + 13 + 14 + 15 + 16 ─────────────> 21 Diagnostic failure injection │
01~21 ──────────────────────────────────────────> 22 CI/deploy gate                │
10 + 13 + 14 + 16 + 22 ────────────────────────> 23 Real Provider admission       │
```

## 状态

| # | 工单 | Blocked by | 状态 |
|---:|---|---|---|
| 01 | [领域词汇与不可变模型目录](issues/01-domain-catalog.md) | — | done |
| 02 | [诊断纯 DDL](issues/02-diagnostic-ddl.md) | — | done |
| 03 | [Provider seam 与可编程假实现](issues/03-provider-seam-and-fake.md) | 01 | done |
| 04 | [诊断快照与异步 writer](issues/04-diagnostic-snapshot-writer.md) | 01, 02 | done |
| 05 | [只读调度总览 API](issues/05-diagnostics-overview-api.md) | 01, 04, 09 | ready |
| 06 | [单候选同步 Chat tracer](issues/06-sync-chat-tracer.md) | 03, 04, 08 | done |
| 07 | [确定性 RoutePlan](issues/07-deterministic-route-plan.md) | 01 | done |
| 08 | [Deadline、attempt 预算与错误动作](issues/08-deadline-budget-error-actions.md) | 01 | done |
| 09 | [Breaker 注册表、原子许可与转换](issues/09-breaker-registry-permissions.md) | 01, 04, 08 | done |
| 10 | [同步重试与故障转移](issues/10-sync-retry-failover.md) | 06, 07, 08, 09 | done |
| 11 | [WebClient 与 SSE Provider](issues/11-webclient-sse-provider.md) | 03 | done |
| 12 | [单候选流状态机与单次订阅](issues/12-single-subscription-stream-state.md) | 04, 08, 11 | ready |
| 13 | [流式路由与故障转移](issues/13-stream-routing-failover.md) | 07, 08, 09, 12 | blocked |
| 14 | [Embedding 批次与 mixed execution](issues/14-embedding-mixed-execution.md) | 03, 04, 07, 08, 09, 10 | ready |
| 15 | [Phase 1 Embedding 原子迁移](issues/15-phase1-embedding-atomic-migration.md) | 14 | blocked |
| 16 | [Rerank 与显式 Noop](issues/16-rerank-and-noop.md) | 10 | ready |
| 17 | [Trace calls/detail API](issues/17-trace-calls-detail-api.md) | 04, 10, 13, 14, 15, 16 | blocked |
| 18 | [A — Trace Console 前端](issues/18-trace-console-frontend.md) | 05, 17 | blocked |
| 19 | [诊断保留与分批清理](issues/19-diagnostic-retention.md) | 02, 04 | ready |
| 20 | [诊断隐私审计](issues/20-diagnostic-privacy-audit.md) | 05, 10, 13, 14, 15, 16, 17, 18 | blocked |
| 21 | [诊断故障注入与 best-effort 证明](issues/21-diagnostic-failure-injection.md) | 04, 05, 10, 13, 14, 15, 16 | blocked |
| 22 | [CI 与部署门禁](issues/22-ci-and-deployment-gates.md) | 01~21 | blocked |
| 23 | [真实 Provider 准入](issues/23-real-provider-admission.md) | 10, 13, 14, 16, 22, compliant key | blocked |

## 推进规则

- Step C 外部闸门已通过；01/02/03/04/06/07/08/09/10/11 已完成，当前 05/12/14/16/19 为 `ready`，更下游工单继续等待显式依赖完成。
- 一张工单只提交一个可验证变化，完成其 Acceptance 后才能解锁下游。
- 发现需要同时修改下游契约时，先回到本依赖图调整，不把隐藏依赖塞进当前提交。
- 测试矩阵中的用例 ID 是验收依据；契约修订新增的 route event、failure phase、mixed execution 和 single-subscription 用例必须先进入矩阵。
- 真实 Provider 不进入普通 CI。工单 22 可在无 Key 时完成；没有合规 Key 时仅工单 23 保持 blocked。
