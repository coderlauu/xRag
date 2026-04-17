# Phase 3A Architecture

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** draft
**对应文档：**
- [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
- [Phase 3A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
- [v7 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md)
- [Phase 3A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-p0-technical-tradeoffs.md)
- [Ask Active Session Stuck Polling Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)
- [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
- [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 3A` 进入 contract freeze 前最关键的架构问题：

- `Phase 3A` 应该复用哪些现有读面，而不是重造一套诊断系统
- `diagnostic sample` 应该是新表、前端临时对象，还是正式 API read model
- answer replay、document replay、deployment compare 应该继续靠页面拼装，还是变成 `ops` 下的新资源族

本阶段不冻结最终 DTO 细节，但要冻结架构方向和边界。

---

## 2. 当前工程事实

1. 当前代码已经存在可直接复用的问答读面：
   - `GET /api/v1/answers/:sessionId`
   - `GET /api/v1/answers/:sessionId/retrieval`
2. 当前代码已经存在可直接复用的文档读面：
   - `GET /api/v1/documents/:documentId`
   - `GET /api/v1/documents/:documentId/evidence`
   - `GET /api/v1/documents/:documentId/timeline`
3. 当前代码已经存在治理主板读面：
   - `GET /api/v1/ops/overview`
   - `GET /api/v1/ops/trends`
   - `GET /api/v1/ops/incidents`
   - `GET /api/v1/ops/deployments/latest`
4. 当前数据面已经具备 `Phase 2C` 引入的持久事实：
   - `evaluation_runs`
   - `deployment_records`
5. 当前 Web `ops` 页面已经切到 `overview + trends`，并把 `Ask / Search / Detail` 的治理提示保持为 prompt-only notice，而不是内部诊断主界面。
6. 当前仓库仍然没有正式的：
   - `diagnostic_sample`
   - session replay read model
   - document replay read model
   - deployment compare read model
7. 当前 Ask 页面按 answer session active status 轮询，若后端长期不写入 terminal status，会暴露为“检索中”无限等待。

结论：`Phase 3A` 不是从零起步，而是要把已有的分散读面组织成统一诊断工作流。

---

## 3. 架构问题与结论

### 3.1 是否新建独立诊断系统

**备选**

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 前端继续直接拼 `answers + documents + ops` | 实现看似最快 | 会把上下文继承、时间窗口、severity 规则再次散落到前端 | 不推荐 |
| 新建独立诊断后端和第二套 explain pipeline | 结构上看似完整 | 事实链分叉，严重破坏可信边界 | 禁止 |
| 在现有 API 内新增 `ops` 下的诊断 read model | 复用既有事实链，同时把上下文继承放回后端 | 需要新增聚合层与明确对象边界 | 推荐 |

**推荐结论**

- `Phase 3A` 继续复用 `answers / documents / ops / evaluation_runs / deployment_records` 现有事实链。
- 新能力以 `ops` 下的 additive read model 实现，不引入第二套诊断系统，不让前端重新承担对象拼装职责。

### 3.2 `diagnostic_sample` 的定位

**结论**

- `diagnostic_sample` 是正式 API read model，不是新表，也不是前端临时对象。
- `diagnostic_sample` 用于统一表达“可从聚合风险进入的具体调查对象”。

**推荐语义**

- `sample_kind`
  - `answer_session`
  - `document_pipeline`
- `sample_id`
  - 采用确定性主键格式：
    - `answer_session:{session_id}`
    - `document:{document_id}`
- `origin`
  - 至少支持：
    - `trend`
    - `incident_cluster`
    - `release_compare`

**原因**

- `deployment compare` 是一个 compare view，不是样本本体；它应该输出样本列表，而不是成为第三种样本表。
- 把样本稳定为 `answer_session / document_pipeline` 两类，可以避免 `P0` 过早演化成通用 observability entity 仓库。

### 3.3 replay 资源是否继续沿用现有 answer/document API

**结论**

- 现有 `/answers/:sessionId`、`/answers/:sessionId/retrieval`、`/documents/:documentId`、`/documents/:documentId/evidence`、`/documents/:documentId/timeline` 继续保留为事实面。
- `Phase 3A` 需要在 `ops` 下新增 replay read model，把这些事实按诊断工作流组织起来。

**原因**

- 直接让 Web 调四到五个接口再本地合并，会再次造成：
  - 时间窗口不一致
  - deployment 上下文丢失
  - incident / severity 规则分散
- replay 需要先回答“为什么坏”，而不是把用户丢给多个事实页自己拼。

### 3.4 deployment compare 的架构位置

**结论**

- deployment compare 以 `deployment_records` 为唯一 anchor。
- compare 视图围绕一个 deployment record 展开等宽 before/after 窗口，再映射出受影响的 `diagnostic_sample` 列表。

**原因**

- `workflow_run_id`、镜像 tag、smoke 结果已经都聚合在 `deployment_records`，比环境变量或前端时间点更稳定。
- 以 deployment record 为 anchor，可以让 compare 与 release guard 保持同一事实链。

**实施约束**

- compare 只能回答：
  - 当前 deployment 周围出现了哪些样本级异常
  - 哪些更像新增回归，哪些更像旧债务延续
- compare 不承担自动回滚与编排职责。

### 3.5 内部诊断和终端主流程的边界

**结论**

- `ops` 仍是内部诊断主入口。
- `Ask / Search / Detail` 最多保留轻量 notice 和跳转，不承载 replay 或 sample list。

**原因**

- `Phase 2A / 2B / 2C` 已冻结终端可信边界。
- `Phase 3A` 的目标是加快内部定位，不是把主产品路径改造成值班台。

### 3.6 Ask active-session reliability guardrail

**结论**

- `P0-G1` 必须作为 Phase 3A 的可靠性 guardrail 进入实现范围。
- 任意 answer session 从 `idle / retrieving / synthesizing` 进入 active 处理后，都必须存在服务端终态收口路径。
- 不可恢复的 active session 统一收口到既有 terminal status `failed`，并通过 `diagnosis_code` 表达 queue、worker、provider 或 retrieval 失败原因。
- 前端轮询兜底只负责停止无限请求和提示诊断入口，不能成为业务事实源。

**原因**

- Answer replay 的前提是 session 有可信 terminal state；永久 active 会让 replay 无法解释当前事实。
- Queue-driven feature 不能假设 worker 一定会消费、成功写回或正常退出。
- 新增 `timed_out`、`stuck` 等 enum 会扩大 contract surface，不能解决真正的 liveness 问题。

**实施边界**

- 不新增 answer session status。
- 不新增 durable table。
- 不把自动 retry、自动 rerun、自动 reindex 或自动 remediation 混入本轮。
- 必须补 worker / queue / web 相关测试，证明 stuck session 最终停止。

---

## 4. 推荐总体架构

```text
Browser
  -> Web SPA
      -> Ask / Search / Detail
          -> light governance notice only
      -> Ops
          -> overview / trends
          -> diagnostic sample list
          -> answer session replay
          -> document pipeline replay
          -> deployment compare
  -> API
      -> existing fact resources
          -> /answers/:sessionId
          -> /answers/:sessionId/retrieval
          -> /documents/:documentId
          -> /documents/:documentId/evidence
          -> /documents/:documentId/timeline
          -> /ops/overview
          -> /ops/trends
          -> /ops/incidents
      -> new ops diagnostic read models
          -> /ops/samples
          -> /ops/replays/answer-sessions/:sessionId
          -> /ops/replays/documents/:documentId
          -> /ops/deployments/compare
  -> PostgreSQL
      -> answer_sessions / retrieval_runs / retrieval_run_hits / answer_claims / answer_citations
      -> documents / document_processing_events / document_parse_jobs / document_source_fetches / document_chunks
      -> evaluation_runs / deployment_records
```

---

## 5. Freeze-Ready 结论

1. `Phase 3A` 应被视为“read model 增量版本”，不是“新建诊断平台版本”。
2. `diagnostic_sample` 作为 read model 成立，默认不新增 durable core table。
3. replay 必须在 `ops` 下形成新的只读聚合资源，而不是回到前端多接口拼装。
4. deployment compare 应固定以 `deployment_records` 为 anchor，不能继续只靠 latest deployment 环境变量。
5. 当前没有新的硬 blocker；下一步可以进入 `contract-freeze`，把对象语义、路径和 query 形状正式锁死。
