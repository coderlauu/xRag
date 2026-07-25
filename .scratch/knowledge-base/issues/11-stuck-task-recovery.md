# 11 — 卡死任务回收

**What to build:** 处理中的文档不会永久卡住。进程崩溃或任务卡死后，文档最终会回到「失败」状态，用户可以重试。

没有这个机制，任何一次崩溃都会让那份文档永久无法操作——CAS 永远抢不到，删除和更新也都被 `409` 挡住。

**Blocked by:** 10（异步入库）

**Status:** ready-for-agent

- [ ] **启动回收**：应用启动后把所有 `status='RUNNING'` 的 `ingestion_run` 与对应文档标记为 `FAILED`，`errorMessage` 说明是进程重启导致
- [ ] **心跳超时回收**：`@Scheduled` 把 `heartbeat_time` 超过 5 分钟未更新的 `RUNNING` 任务标记 `FAILED`
- [ ] 两层回收都要同时更新 `ingestion_run` 和 `source_document`，不能只改一边（只改 run 会让文档永久卡在 `RUNNING`）
- [ ] 超时阈值可配置（`app.knowledge.ingestion.heartbeat-timeout`）
- [ ] 集成测试：手工插入一条 `RUNNING` 且 `heartbeat_time` 为 6 分钟前的 run，触发回收后确认它与对应文档都变 `FAILED`
- [ ] 集成测试：回收后该文档可以被重新触发分块（证明 CAS 不再被挡住）
- [ ] 手工验证：分块处理进行到中途 `kill -9` 应用进程，重启后确认该文档是 `FAILED` 而不是 `RUNNING`
- [ ] 在代码注释中标注启动回收依赖"单实例部署"这一假设，并指向 [ADR 0002](../../../docs/adr/0002-knowledge-base-async-and-concurrency.md)——多实例下这段逻辑会误伤其他实例正在运行的任务
- [ ] `./mvnw -q -B verify` 通过
