# Ask Active Session Stuck Polling Retrospective

**日期：** 2026-04-17  
**版本上下文：** `v7 / Phase 3A` implementation lanes  
**状态：** active  
**关联事项：** `P0-G1 Ask active-session 终态收口与轮询兜底`

---

## 1. 一句话结论

本次问题不是单纯的前端轮询 bug，而是异步 answer session 状态机缺少“活跃态必须被服务端收口到终态”的可靠性约束；前端按状态继续轮询只是把后端未收口的问题暴露成用户可见的“检索中”无限等待。

---

## 2. 用户影响

### 直接影响

- Ask 页面会持续显示检索中或合成中。
- 页面会继续周期性调用 answer session、retrieval trace 和 history 相关接口。
- 用户无法判断系统是在正常处理、队列阻塞、worker 失败，还是已经不可恢复。

### 间接影响

- Answer replay 会拿到一个没有终态的 session，降低诊断视图可信度。
- retrieval 为空时容易被误判成“还在等待证据”，而不是“任务已经卡死”。
- Phase 3A 的样本回放和诊断能力如果不处理该问题，会把 stuck session 当成可调查样本，却无法给出明确根因。

---

## 3. 已确认事实

1. Web Ask 页面以 `AnswerSessionStatus` 判断是否继续轮询。
2. `idle`、`retrieving`、`synthesizing` 被视为 active status。
3. `answered`、`needs_scope`、`refused`、`failed` 被视为 terminal status。
4. 前端当前没有最大轮询时长或 stuck-session fallback。
5. API 创建 answer session 后会入队 BullMQ job，并把 session 更新为 `retrieving`。
6. Worker 成功执行后应把 session 写成 `answered`、`needs_scope`、`refused` 或 `failed`。
7. 如果 job 未被消费、worker 崩溃、BullMQ job stalled/failed 未回写数据库，或异常发生在未覆盖的 handler 外，session 可能长期停留在 active status。
8. 当前没有专门测试覆盖“active session 必须在失败或超时后进入 terminal status”。

---

## 4. 根因分析

### A. 前端轮询契约不完整

前端把“非终态继续轮询”作为唯一控制条件，但没有客户端最大轮询时长、stuck 提示或降级 UI。

这不是根因的全部，因为前端不能决定真实 session 终态；但它会放大后端未收口问题，使用户看到无限等待。

### B. 后端异步状态机缺少 liveness 保证

API 把 session 推到 `retrieving` 后，后续终态依赖 worker 正常消费和写回。系统缺少一个独立约束：

> 任意 `idle / retrieving / synthesizing` session 必须最终进入 terminal status，或被服务端 reconciliation 标记为 `failed`。

没有这条约束，队列失败、worker 异常和 DB 写回失败都会把用户状态留在 active。

### C. Queue 事件没有和业务状态对账

BullMQ job failed、stalled 或 exhausted retries 本身不是业务终态。除非事件处理或补偿任务把对应 answer session 写成 `failed`，数据库仍会显示 active。

### D. Worker 失败边界不够外包

Worker 内部已有部分 try/catch 会把异常映射为 `failed`，但任何发生在通用保护区之外的异常仍可能导致 session 没有被写回终态。

### E. 测试没有覆盖“最终会停止”

已有测试更偏向成功、无证据拒答和常规失败路径，没有把 stuck active session 作为必须失败的回归场景。

---

## 5. 当前迭代修复要求

`v7 / Phase 3A` 必须新增 `P0-G1`，作为本轮实现项，而不是只留在聊天记录里。

### 服务端要求

1. 不新增 `AnswerSessionStatus` enum 值。
2. 不引入 `stale`、`timed_out` 等新 session status。
3. 任何超时、stalled、failed 或不可恢复的 active session，必须映射到现有 terminal status `failed`。
4. `diagnosis_code` 应尽量复用现有机器可读分类，例如 queue backlog、provider timeout、retrieval failure 或 generic failure。
5. BullMQ failed/stalled/exhausted retry 事件需要与 `answer_sessions` 做状态对账。
6. Worker handler 需要覆盖进入处理后的前置失败，避免 active status 写入后没有终态。

### 前端要求

1. 前端轮询必须有最大等待或 stuck fallback。
2. 前端 stuck fallback 只能停止无意义轮询并提示诊断入口，不能伪造服务端终态。
3. Ask 页面应让用户能区分正常检索、长时间未完成和已失败。

### 测试要求

1. 覆盖 worker 异常后 session 进入 `failed`。
2. 覆盖 queue failed/stalled 对账后 session 进入 `failed`。
3. 覆盖前端 active session 超过客户端阈值后停止无限轮询并展示 stuck 提示。
4. 覆盖 replay 或 ops 诊断读取 stuck/failed session 时不生成与真实 facts 冲突的状态。

---

## 6. 对 Phase 3A 的影响

该问题不改变 `P0-01 ~ P0-04` 的诊断产品方向，但会成为这些能力的可靠性前提。

原因：

1. Answer session replay 必须能看到明确 terminal state。
2. Diagnostic sample list 不能把永久 active session 当作正常处理中样本。
3. Deployment compare 不能把队列/worker 卡死误判为质量回归。
4. `ops` drill-down 的目标是缩短定位时间，不能继续制造“还在处理”的假象。

因此，`P0-G1` 必须在进入并行实现 lane 前由主线程纳入计划和验收。

---

## 7. 未来开发检查清单

以后任何涉及异步状态、队列、轮询或长任务 UI 的功能，都必须先回答：

1. 哪些状态是 active，哪些状态是 terminal？
2. active 状态的最长允许存在时间是多少？
3. 谁负责把超时或不可恢复状态写成 terminal？
4. 如果 worker 从未消费 job，数据库状态如何收口？
5. 如果 job failed、stalled 或 retries exhausted，业务状态如何对账？
6. 前端轮询的停止条件是什么？
7. 用户看到 stuck 状态时，下一步能去哪里诊断？
8. 哪个自动化测试证明“最终会停止”？
9. 是否真的需要新增 enum，还是可以复用已有 terminal status 加 diagnosis？
10. 诊断页面展示的是事实，还是前端自行推断？

---

## 8. 永久改进项

1. 把“异步 active 状态必须有服务端终态收口”写入工程 playbook。
2. 对 queue-driven feature 默认要求 failed/stalled reconciliation。
3. 对 polling UI 默认要求客户端最大等待和可解释 fallback。
4. 对 answer/retrieval 相关功能默认要求 answer-quality gate 验证，不允许隐藏 freshness、retrieval empty 或 failed 事实。
5. 对状态机变更默认要求 contract guard 审查，不通过新增 enum 掩盖 liveness 问题。

---

## 9. Resume 入口

后续新 session 继续处理该问题时，先读：

1. [Phase 3A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md)
2. [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md)
3. [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
4. 本复盘文档

重点检查代码路径：

1. `apps/web/src/features/answers/pages/ask-page.tsx`
2. `apps/web/src/lib/answer-state.ts`
3. `apps/api/src/answers/answers.service.ts`
4. `apps/api/src/queue/queue.service.ts`
5. `apps/worker/src/answers/answer-orchestration.ts`
6. `apps/worker/src/worker/bootstrap.ts`
