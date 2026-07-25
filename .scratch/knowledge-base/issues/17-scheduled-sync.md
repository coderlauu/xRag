# 17 — 定时同步

**What to build:** 用户给一个 URL 来源文档配上 cron，源文件更新后知识库内容自动跟着刷新，不用手动重新上传。界面上能看到下次执行时间、上次执行结果和失败历史。

关键要求是**先判断值不值得刷新，再决定要不要真正处理**——每次定时触发都无脑重新下载、解析、向量化，会白花大量 Embedding 费用。

**Blocked by:** 16（URL 来源文档）、11（卡死回收——定时同步必须有超时恢复兜底）

**Status:** ready-for-agent

- [ ] `@Scheduled` 每 60s 扫描：`source_type='URL' and sync_enabled and deleted=false and status<>'RUNNING' and next_sync_time <= now()`
- [ ] **两级变更检测**：先 HEAD 比对 `ETag`/`Last-Modified`；未变则跳过；变了或 HEAD 不可用则下载并比对 SHA-256 内容哈希；哈希相同仍然跳过
- [ ] 两级都跳过时记录 `ingestion_run(status='SKIPPED')` 并推进 `next_sync_time`。**`SKIPPED` 必须记录**——否则用户看到"同步开着但从来没有执行记录"，无法区分"检查过没变化"和"调度根本没跑"
- [ ] 确认变化后走工单 10 的同一条执行链路（CAS 抢占 + 插入 `run(trigger_source='SCHEDULED')`），**不写第二套处理逻辑**
- [ ] 定时同步与手动触发的互斥完全由工单 10 的同一个 CAS 保证，**不加任何额外锁**（理由见 [ADR 0002](../../../docs/adr/0002-knowledge-base-async-and-concurrency.md)）
- [ ] cron 最短间隔校验：解析出的相邻两次执行间隔小于 `app.knowledge.sync.min-interval`（默认 10 分钟）时返回 `400`，防的是 `* * * * * ?` 这种把 Embedding 费用打穿的表达式
- [ ] 每次检查（含跳过）都推进 `next_sync_time` 与 `last_sync_time`
- [ ] 前端：定时同步配置表单 + 状态展示（下次执行时间、上次执行结果、失败历史），`SKIPPED` 按 `ui-spec.md` 显示为"内容未变化"而不是失败
- [ ] 集成测试：cron 间隔小于下限时返回 `400`
- [ ] 集成测试：内容哈希与库中一致时产生一条 `SKIPPED` 记录，且 `document_chunk` 无任何变化
- [ ] **手工验证（成对做，两次行为必须不同）**：① 源文件不变，等一次同步触发，确认产生 `SKIPPED` 且分块未被重建；② 修改源文件内容，再等一次触发，确认真的重新分块且 `revision` 递增
- [ ] 手工验证：给同一份文档同时手动触发和等定时触发，确认只有一个真正执行、另一个被 CAS 挡住
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
