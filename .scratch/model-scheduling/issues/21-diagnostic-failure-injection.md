# 21 — 诊断故障注入与 best-effort 证明

**What to build:** 注入 writer、队列、子表和进程故障，证明完成态诊断 best effort 不改变四能力端口结果，并使丢弃/失败事实在 overview 可见。

**Blocked by:** 04, 05, 10, 13, 14, 15, 16

**Status:** blocked

## Slice

- 注入 writer 数据库不可用、队列满和子表约束失败。
- 验证一条诊断事务原子回滚，主调用线程 offer 不阻塞、不抛业务异常。
- 优雅停机最多等待 2 秒 flush，不无限阻塞。
- 手工 kill 进程，确认允许缺少在途诊断且重启后不伪造记录。
- overview 暴露 dropped/write-failure 计数，读取失败时不伪造为零。

## Acceptance

- [ ] DIA-07~DIA-09、DIA-17~DIA-18 与 AC-17 有证据。
- [ ] 诊断故障时 Chat、Stream、Embedding、Rerank 的能力结果与无故障时一致。
- [ ] 子表写失败不留下半条 call；队列满不阻塞调用线程。
- [ ] dropped/write-failure 计数可观测，重复日志受节流。
- [ ] `./mvnw -B verify` 通过。

**Design:** [data model §5](../../../tech/model-scheduling/data-model.md#5-写入模型)
