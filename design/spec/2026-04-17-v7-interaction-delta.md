# v7 Interaction Delta

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** draft
**对应 PRD：** [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
**对应 Backlog：** [Phase 3A Product Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
**上游交互基线：** [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)

---

## 1. 文档目的

本说明只定义 `v7 / Phase 3A` 相对 `v6` 的交互增量，不重写整份页面全景。

它解决的问题是：

- `ops` 主板如何从“聚合治理页”升级为“可深钻的诊断入口”
- 样本级 drill-down、session replay、document replay、release compare 应如何分工
- 哪些内部诊断入口可以被 `Ask / Search / Detail` 轻量感知，哪些必须严格留在内部

---

## 2. 不变前提

以下基线在 `Phase 3A` 不改变：

- `Ask / Search / Detail` 仍然是终端用户的主入口
- `answered / needs_scope / refused / failed` 的结果语义不变
- `citation / refusal / freshness / release-readiness` 仍是正式边界
- `ops` 继续定位为内部治理与诊断入口，而不是主要问答入口
- 不展示 prompt、chain-of-thought、自动修复动作

---

## 3. 新增核心对象

### `diagnostic_sample`

用于表达一个可被追踪和回放的异常样本。

用户可感知字段至少包括：

- `sample_id`
- `source_type`
- `source_ref`
- `first_seen_at`
- `last_seen_at`
- `severity`
- `related_deployment_window`

### `answer_session_replay`

用于回放单次问答从 query 到 terminal state 的关键链路。

用户可感知字段至少包括：

- `session_id`
- `query`
- `scope_snapshot`
- `retrieval_summary`
- `citation_summary`
- `terminal_state`
- `freshness_flags`

### `document_pipeline_replay`

用于回放单个 document 在 ingest / indexing 链路上的关键状态。

用户可感知字段至少包括：

- `document_id`
- `processing_state_timeline`
- `indexing_state_timeline`
- `freshness_state`
- `blocking_reason`
- `last_transition_at`

### `deployment_compare_window`

用于围绕某次 deployment 对比前后窗口并定位受影响样本。

用户可感知字段至少包括：

- `deployment_record_id`
- `before_window`
- `after_window`
- `affected_sample_count`
- `quality_delta_summary`
- `incident_delta_summary`

---

## 4. 页面增量

### 4.1 `ops.html` 诊断入口增强

#### 新增 / 强化模块

1. 从 trends / incident / release guard 进入样本级 drill-down
2. 诊断样本列表
3. 跳转到 session replay / document replay / deployment compare 的导航入口

#### 交互要求

- 首屏仍先回答“现在哪里最危险”，但高风险聚合块必须有明确的 drill-down 入口
- drill-down 必须继承时间窗口、cluster 和 deployment 上下文
- 同一异常样本应能在多个入口共享统一标识，而不是各自重新编号

#### 明确禁止

- 把 `ops.html` 做成自由文本日志浏览器
- 在主板里直接展示过深的原始调试细节
- 把内部诊断动作包装成自动修复按钮

### 4.2 `ops.html` 下的 session replay 视图

#### 新增 / 强化模块

1. session 基础摘要
2. retrieval / citation / freshness / refusal 时间线
3. 上下游跳转：返回样本列表、进入关联 document 或 deployment compare

#### 交互要求

- 回放应先回答“这次 answer 为什么坏”，再展示更细的链路
- retrieval、citation 与 terminal state 应并列可读，而不是分散到多个位置
- replay 视图只读，不承担重新执行或编辑职责

### 4.3 `ops.html` 下的 document replay 视图

#### 新增 / 强化模块

1. document 基础摘要
2. `upload -> processing -> indexing` 状态时间线
3. freshness / stale / failure 阻断提示
4. 关联 incident 或 readiness 入口

#### 交互要求

- 应能快速区分 backlog、失败、stale 和 freshness 漂移
- document replay 继续以事实回放为主，不承担批量运维控制台职责

### 4.4 `ops.html` 下的 deployment compare 视图

#### 新增 / 强化模块

1. 当前 deployment 与上一个稳定基线摘要
2. before / after 样本与 cluster 对比
3. 质量变化与 smoke 结果对照

#### 交互要求

- compare 视图先回答“这次发布后新增了什么异常”，再提供样本列表
- 需要清楚区分新回归与旧债务延续

### 4.5 `index.html / search.html / detail.html`

#### 新增 / 强化模块

1. 仅保留最小的内部诊断入口提示
2. 当主板已经判定高风险时，允许引导内部人员跳到 `ops`

#### 交互要求

- 终端用户主流程不复制整套诊断交互
- 轻量提示只表达“内部诊断可用”，不直接暴露复杂运维术语

---

## 5. 关键状态规则

1. 任何诊断结论都必须来自正式事实源，而不是页面静态推断。
2. `ops` 先给聚合判断，再给样本入口，再给回放页面，不反过来。
3. 终端问答页只暴露“会影响用户动作”的最小入口，不承担内部诊断职责。
4. session、document、deployment 三类回放必须共享统一时间窗口和对象标识。
5. 诊断工作流是只读和可解释的，不进入自动处置。

---

## 6. 本轮明确不纳入交互 delta 的内容

- 新的聊天线程体系
- 团队值班协作与评论
- 自动修复按钮
- 自由文本日志检索台
- 在线 golden set 编辑器
- 多模型成本对比大盘
