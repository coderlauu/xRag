# 04 — 诊断快照与异步 writer

**What to build:** 在领域词汇和 V6 Schema 之上实现完成态诊断快照、有界队列与单线程批量 writer，让一条模拟调用的事实原子落库。

**Blocked by:** 无（01、02 已于 2026-08-02 完成）

**Status:** done（2026-08-03）

## Slice

- 定义最小化 `CompletedCallDiagnostic`，包含 call、candidate、ordered route events、attempts、failure phase 与进程内 Embedding per-batch execution。
- `CallDiagnosticBuilder` 只接收脱敏稳定事实，不含正文、原始错误或 secret 字段。
- 容量 1,000，非阻塞 offer；250ms/50 条批写；关闭最多 flush 2s。
- 一条逻辑调用的所有子记录在一个短数据库事务写入；外部 Provider 调用不处于该事务中。
- 暴露 dropped/write-failure 内存计数并对重复日志节流。

## Acceptance

- [x] 成功、attempt=0、route permission reject、failure phase 和 mixed Embedding 快照均能完整写入。
- [x] writer 由成功 attempt 的 `work_unit_index` 写入贡献事实，并在 call 上写 mixed/count 摘要；不维护独立 contribution 表。
- [x] call/candidates/events/attempts 任一子记录失败时整条事务回滚。
- [x] 队列满或数据库不可用时 offer 不阻塞、不改变主调用结果。
- [x] 诊断 DTO 编译期不存在正文、原始错误或秘密承载字段。
- [x] DIA-02、DIA-04、DIA-07~DIA-09、DIA-17 自动化。

**Design:** [data model §5](../../../tech/model-scheduling/data-model.md#5-写入模型)
