# v6 Interaction Delta

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** draft
**对应 PRD：** [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
**对应 Backlog：** [Phase 2C Product Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
**上游交互基线：** [v5 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)

---

## 1. 文档目的

本说明只定义 `v6 / Phase 2C` 相对 `v5` 的交互增量，不重写整份页面全景。

它解决的问题是：

- `ops.html` 如何从“摘要页”升级为“治理主板”
- 哪些轻量状态值得暴露到 `index.html / search.html / detail.html`
- 内部治理交互如何保持与 `Phase 2A / 2B` 的可信问答主链分离

---

## 2. 不变前提

以下基线在 `Phase 2C` 不改变：

- `Ask / Search / Detail` 仍然是终端用户的主入口
- `answered / needs_scope / refused / failed` 的结果语义不变
- `citation / refusal / freshness / release-readiness` 仍是正式边界
- `ops.html` 继续定位为内部治理入口，而不是主要问答入口
- 不展示 prompt、chain-of-thought、自动修复动作

---

## 3. 新增核心对象

### `corpus_readiness_snapshot`

用于表达当前知识库是否具备稳定问答条件。

用户可感知字段至少包括：

- `ready_document_count`
- `embedding_backlog`
- `stale_document_count`
- `failed_document_count`
- `readiness_rate`
- `freshness_lag_p95`
- `blocking_reason`

### `quality_scorecard`

用于表达可信问答质量是否稳定。

用户可感知字段至少包括：

- `groundedness`
- `citation_coverage`
- `refusal_precision`
- `latency_p50`
- `latency_p95`
- `avg_token_cost_usd`
- `trend_window`
- `drift_badge`

### `incident_cluster`

用于把 incident 按来源、严重度和影响面组织起来。

用户可感知字段至少包括：

- `cluster_key`
- `source`
- `severity`
- `incident_count`
- `affected_surface`
- `recommended_action`

### `release_guard`

用于把最新部署、smoke、健康状态与质量漂移放到同一决策块里。

用户可感知字段至少包括：

- `current_image_tag`
- `previous_stable_image_tag`
- `last_smoke_status`
- `service_health_summary`
- `post_deploy_risk`

---

## 4. 页面增量

### 4.1 `ops.html` 治理主板

#### 新增 / 强化模块

1. 语料就绪度总览
2. 质量评分卡与趋势
3. Incident 聚类区
4. 发布守门卡片
5. 推荐动作区

#### 交互要求

- 页面首屏应先回答“现在能不能答、哪里最危险”，而不是先展示所有原始数字
- 语料就绪度、质量趋势、incident 聚类、部署摘要必须能同屏对照
- 高风险问题应优先以 `badge / summary / action` 呈现，而不是藏在下层列表
- 推荐动作必须基于当前主板事实，不能是通用模板堆砌
- 每个治理模块都应保留进一步 drill-down 的预留入口，但 `Phase 2C` 首版不强制实现所有深钻页

#### 明确禁止

- 把 `ops.html` 做成原始日志浏览器
- 独立生成一套与真实运行事实不一致的“解释故事”
- 让治理页面承担自动修复职责

### 4.2 `index.html` 问答工作台

#### 新增 / 强化模块

1. 轻量 corpus readiness 提示
2. 高风险质量退化提示

#### 交互要求

- 只有当治理状态足以影响 Ask 行为时，才显示轻量提示
- 提示应明确区分“资料未就绪”“服务异常”“质量观察中”等不同原因
- 提示可以引导用户或内部人员跳到 `ops.html`，但不在问答页塞入完整治理面板

#### 明确禁止

- 在问答页复制整套 `ops` 交互
- 用内部运维术语污染终端用户主流程

### 4.3 `search.html` 检索实验台

#### 新增 / 强化模块

1. 与 corpus readiness 对齐的轻量状态提示
2. 与质量退化相关的入口提示

#### 交互要求

- 检索实验台应能解释“当前为什么难以得到可用结果”，但不替代 `ops.html`
- 若 readiness 阻断或 incident 高发，应能从 search 进入治理页

### 4.4 `detail.html` 详情页

#### 新增 / 强化模块

1. 与当前 document freshness / index readiness 对齐的状态提示
2. 从证据详情进入治理页的上下文入口

#### 交互要求

- 若某文档因 stale 或 failed 明显影响当前问答，应在详情页保持可见
- 详情页仍以证据定位为主，不承载完整治理信息

---

## 5. 关键状态规则

1. 任何治理结论都必须来自正式事实源，而不是静态文案推断。
2. `ops.html` 先给聚合判断，再给 drill-down 入口，不反过来。
3. 问答页只暴露“影响用户动作”的最小治理提示，不承担内部诊断职责。
4. 质量指标、deployment、incident、readiness 之间必须共享统一时间窗口和状态口径。
5. 当主板结论是“目前不可问”时，优先给出原因和下一步动作，而不是继续强化生成体验。

---

## 6. 本轮明确不纳入交互 delta 的内容

- 新的聊天线程体系
- 团队值班协作与评论
- 自动修复按钮
- provider 级 debug 控制台
- 在线 golden set 编辑器
- 多模型成本对比大盘
