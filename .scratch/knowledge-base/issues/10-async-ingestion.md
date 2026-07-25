# 10 — 异步入库全链路

**What to build:** 用户点「开始分块」，接口立刻返回，界面显示「处理中」并自动轮询；几十秒后状态变成「成功」，分块数出现在列表里。这是整个模块的核心路径。

失败时状态变成「失败」并能看到失败在哪一步，用户可以直接重试而不需要重新上传。

**Blocked by:** 06（Embedding 客户端）、07（文件上传）、09（提取与分块）

**Status:** ready-for-agent

- [ ] 后端 `POST /api/v1/documents/{docId}/ingestion-runs`，返回 `202` + `runId`
- [ ] **CAS 抢占**：`update source_document set status='RUNNING' where id=? and deleted=false and status<>'RUNNING'`，影响行数为 0 → `409 DOCUMENT_PROCESSING`。同一事务内插入 `ingestion_run(status='QUEUED')`
- [ ] 文档 `enabled=false` 时拒绝触发（`409 INVALID_STATE`）——给禁用文档分块会写入向量，与"禁用即不参与检索"直接矛盾
- [ ] `@Scheduled` 每 2s 轮询 `status='QUEUED'`，逐条 CAS 抢占为 `RUNNING` 后提交给线程池
- [ ] `executeRun` 严格按六步执行，**第 4 步批量 Embedding 必须在事务外算完，第 5 步才开事务写库**（详见 [architecture.md §3.2](../../../tech/knowledge-base/architecture.md)）
- [ ] 第 5 步一个事务内完成：逻辑删除旧 `revision` 分块 + 物理删除其向量 + 插入新分块 + 插入新向量 + 更新文档为 `SUCCESS`/`revision+1`/`chunkCount`
- [ ] 首次分块与重新分块走**完全同一段代码**（首次时旧分块集合为空，不需要分支判断）
- [ ] 每一步执行前更新 `ingestion_run.phase`，失败时能说明失败在哪一步
- [ ] 失败处理在**新事务**中标记 `ingestion_run=FAILED` + `errorMessage` + 文档 `status=FAILED`（不用新事务的话失败信息会被外层回滚一起冲掉）
- [ ] 执行线程每 10s 更新 `heartbeat_time`
- [ ] 后端 `GET /api/v1/documents/{docId}/ingestion-runs` 历史列表；`GET /api/v1/documents/{docId}` 带 `latestRun` 摘要
- [ ] 前端：触发按钮 + `RUNNING` 时按 2s 轮询文档详情、到 `SUCCESS`/`FAILED` 停止、**组件卸载时清理定时器**
- [ ] 前端展示入库任务历史，`phase` 与 `status` 按 `ui-spec.md` 的中文表述显示
- [ ] 集成测试：`RUNNING` 中重复触发返回 `409`；成功后 `document_chunk` 与 `document_chunk_embedding` 行数一致且都属于新 `revision`；重新分块后旧 revision 分块全部 `deleted=true` 且其向量已被物理删除
- [ ] 集成测试：Embedding 调用抛异常时，文档变 `FAILED`、`ingestion_run.phase='EMBED'`、且**数据库里没有留下任何新分块**（事务回滚干净）
- [ ] 浏览器中完整走通：上传 PDF → 触发分块 → 看到「处理中」→ 自动变为「成功」并显示分块数
- [ ] 浏览器 Network 面板确认轮询在页面切走后停止
- [ ] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过
