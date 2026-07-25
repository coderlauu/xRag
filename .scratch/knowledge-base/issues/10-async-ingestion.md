# 10 — 异步入库全链路

**What to build:** 用户点「开始分块」，接口立刻返回，界面显示「处理中」并自动轮询；几十秒后状态变成「成功」，分块数出现在列表里。这是整个模块的核心路径。

失败时状态变成「失败」并能看到失败在哪一步，用户可以直接重试而不需要重新上传。

**Blocked by:** 06（Embedding 客户端）、07（文件上传）、09（提取与分块）

**Status:** done（`2026-07-25`）

- [x] 后端 `POST /api/v1/documents/{docId}/ingestion-runs`，返回 `202` + `runId`
- [x] **CAS 抢占**：`update source_document set status='RUNNING' where id=? and deleted=false and status<>'RUNNING'`，影响行数为 0 → `409 DOCUMENT_PROCESSING`。同一事务内插入 `ingestion_run(status='QUEUED')`
- [x] 文档 `enabled=false` 时拒绝触发（`409 INVALID_STATE`）——给禁用文档分块会写入向量，与"禁用即不参与检索"直接矛盾
- [x] `@Scheduled` 每 2s 轮询 `status='QUEUED'`，逐条 CAS 抢占为 `RUNNING` 后提交给线程池
- [x] `executeRun` 严格按六步执行，**第 4 步批量 Embedding 必须在事务外算完，第 5 步才开事务写库**（详见 [architecture.md §3.2](../../../tech/knowledge-base/architecture.md)）
- [x] 第 5 步一个事务内完成：逻辑删除旧 `revision` 分块 + 物理删除其向量 + 插入新分块 + 插入新向量 + 更新文档为 `SUCCESS`/`revision+1`/`chunkCount`
- [x] 首次分块与重新分块走**完全同一段代码**（首次时旧分块集合为空，不需要分支判断）
- [x] 每一步执行前更新 `ingestion_run.phase`，失败时能说明失败在哪一步
- [x] 失败处理在**新事务**中标记 `ingestion_run=FAILED` + `errorMessage` + 文档 `status=FAILED`（不用新事务的话失败信息会被外层回滚一起冲掉）
- [x] 执行线程每 10s 更新 `heartbeat_time`
- [x] 后端 `GET /api/v1/documents/{docId}/ingestion-runs` 历史列表；`GET /api/v1/documents/{docId}` 带 `latestRun` 摘要
- [x] 前端：触发按钮 + `RUNNING` 时按 2s 轮询文档详情、到 `SUCCESS`/`FAILED` 停止、**组件卸载时清理定时器**
- [x] 前端展示入库任务历史，`phase` 与 `status` 按 `ui-spec.md` 的中文表述显示
- [x] 集成测试：`RUNNING` 中重复触发返回 `409`；成功后 `document_chunk` 与 `document_chunk_embedding` 行数一致且都属于新 `revision`；重新分块后旧 revision 分块全部 `deleted=true` 且其向量已被物理删除
- [x] 集成测试：Embedding 调用抛异常时，文档变 `FAILED`、`ingestion_run.phase='EMBED'`、且**数据库里没有留下任何新分块**（事务回滚干净）
- [x] 浏览器中完整走通：上传 PDF → 触发分块 → 看到「处理中」→ 自动变为「成功」并显示分块数
- [x] 浏览器 Network 面板确认轮询在页面切走后停止
- [x] `./mvnw -q -B verify` 与 `pnpm build && pnpm lint` 通过

## 完成记录

模块的核心路径打通了：**上传 → 触发 → 提取 → 切分 → 真实向量化 → 写库**，浏览器里可完整演示。

### 一个会静默失效的坑，写代码时当场发现并绕开

`executeSteps` 是同类的私有方法，它调 `this.persist(...)`。**如果 `persist` 用 `@Transactional`，注解会静默失效**——自调用走不到 Spring 的代理。后果不是报错，是第 5 步那五条写入各自独立提交：中途失败就留下"旧分块删了、新分块只插了一半、向量对不上"的残局，而且**没有任何迹象提示事务没生效**。

改用编程式事务（`TransactionTemplate`），同时也让"事务从哪开始、到哪结束"在调用点直接可见——这正是本模块最在意的那条纪律。`markFailed` 同理，用 `PROPAGATION_REQUIRES_NEW` 的独立模板。

### 真实 API 的端到端实测（火山方舟 Ark）

| 用例 | 结果 |
|---|---|
| 601 字符 / 默认 chunkSize 1000 | `SUCCESS`，revision 1，1 个分块 = 1 个向量，`vector_dims` = **1024** |
| 25 段 / chunkSize 60 → 25 个分块 | `SUCCESS`，25 分块 = 25 向量，维度种类只有 1 种（1024）。**跨 3 批真实调用**（10+10+5），验证了分批与顺序 |

界面规则逐条核对（手工把一份文档置为 `RUNNING` 构造状态）：状态显示「处理中」、阶段提示「正在计算向量」（说明轮询确实在拉 `latestRun.phase`）、触发按钮禁用且 tooltip 与后端 409 的 message **逐字一致**。

轮询停止用**应用内路由跳转**测（不是整页刷新——那会重置 performance 时间线，什么都证明不了）：切走后只多了 1 次在途请求就停了。另外量了实际间隔，精确 2000ms。

### 7 条集成测试

用确定性假 Embedding（`@Primary` 覆盖），不打真实 API——这里验的是链路和事务边界，不是模型质量。最值钱的是**失败回滚**那条：让 Embedding 抛异常，断言文档变 `FAILED`、`phase` 停在 `EMBED`、**库里没有留下任何分块**。

**本类不能加 `@Transactional`**：真正的执行在派发器的工作线程里，测试线程的事务对它不可见，加了反而看不到任何结果。代价是自己按 `kb_id` 清理数据。

### 几处值得记的设计

- **心跳独立于执行线程**（单独的 `ScheduledExecutorService`）。执行线程正卡在下载或 Embedding 调用上时心跳也得继续跳，否则心跳超时回收会把一个其实还活着的任务误判成卡死。
- **首次分块与重新分块走完全同一段代码**：`softDeleteOlderRevisions` 在首次时影响 0 行，不需要任何分支判断。
- **启动回收放在 `ApplicationRunner`**，数据库不可达时只记警告——沿用既有的不阻塞启动模式。它的正确性依赖单实例假设（ADR 0002），多实例前必须先改。
- `ingestion_run` 的查询**没有 `deleted = false`**：这张表是日志性质的记录，没有"删除"这个操作。这是有意的，不是漏写。
- `error_message` 列宽 4096，异常链很容易超——超了整条 `update` 会失败，把失败原因也一起弄丢，所以入库前截断。

### 新增配置项（工单 19 需要登记进 env example）

```properties
app.knowledge.ingestion.enabled=${KNOWLEDGE_INGESTION_ENABLED:true}
app.knowledge.ingestion.poll-interval=${KNOWLEDGE_INGESTION_POLL_INTERVAL:2s}
```

`enabled` 不是为了配置而配置，是被上面那个 flaky 逼出来的：`ApplicationTests` 的配置
（`flyway.enabled=false`）注定与集成测试不同、分属两个上下文，共享 config 也救不了，
只能把它的调度器关掉。

### 未做

心跳超时回收（`heartbeat_time` 超 5 分钟判卡死）属于工单 11。本工单只写了心跳的**更新**侧。
