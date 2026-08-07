# 21 — 诊断故障注入与 best-effort 证明

**What to build:** 注入 writer、队列、子表和进程故障，证明完成态诊断 best effort 不改变四能力端口结果，并使丢弃/失败事实在 overview 可见。

**Blocked by:** 04, 05, 10, 13, 14, 15, 16

**Status:** done（2026-08-07）

## Slice

- 注入 writer 数据库不可用、队列满和子表约束失败。
- 验证一条诊断事务原子回滚，主调用线程 offer 不阻塞、不抛业务异常。
- 优雅停机最多等待 2 秒 flush，不无限阻塞。
- 手工 kill 进程，确认允许缺少在途诊断且重启后不伪造记录。
- overview 暴露 dropped/write-failure 计数，读取失败时不伪造为零。

## Acceptance

- [x] DIA-07（队列满 offer 立即失败并计数）、DIA-08（存储失败只计数不回传）、DIA-17（关闭 flush 有上限）由既有 `AsyncDiagnosticWriterTests` 覆盖；DIA-09（子表失败整条回滚）由既有 `DiagnosticWriterIntegrationTests` 覆盖。本工单核对确认，未重复造用例。
- [x] AC-17 的另一半由新增 `DiagnosticBestEffortTests` 补齐：把"诊断彻底坏掉"的 recorder 接到 Chat / Embedding / Rerank / Rerank-Noop 四条路径，逐一比对结果与健康 recorder 完全一致；另有一例覆盖 `offer` 返回 false（队列满）这条与抛异常不同的分支。
- [x] dropped/write-failure 计数经 overview 暴露（工单 05），读取失败返回 null 而不是 0；重复日志由 `AsyncDiagnosticWriter.warnThrottled` 按分钟节流。
- [x] `./scripts/ci-validate.sh` 通过（clean 构建后 backend 399 tests，0 failures，2 skipped）。
- [ ] **DIA-18（进程崩溃）矩阵标记为 M（手工）**，需人为 kill 进程后重启核对页面不伪造记录；本工单未执行，留待 Step E 的手工项一并做。

**Design:** [data model §5](../../../tech/model-scheduling/data-model.md#5-写入模型)


## 实现记录（2026-08-07）

进场先核对了一遍，发现 DIA-07/08/09/17 早已被工单 04 的用例覆盖——**它们证明的是 writer 自己的
行为**。缺的是另一半：writer 再怎么 best effort，只要某个 routed client 忘了把
`diagnostics.offer(...)` 包进 try/catch，一次诊断异常就会顺着调用栈冒出去变成业务失败。

`DiagnosticBestEffortTests` 补的就是这一半，逐能力比对"诊断坏掉"与"诊断健康"两次调用的返回。
Rerank 的 Noop 降级单独一例——降级路径同样走 `recordBestEffort`，最容易在"反正是降级"的心态下
漏掉保护。
