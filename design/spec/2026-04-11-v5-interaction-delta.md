# v5 Interaction Delta

**日期：** 2026-04-11
**版本：** `v5 / Phase 2B`
**状态：** draft
**对应 PRD：** [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
**对应 Backlog：** [Phase 2B Product Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
**上游交互基线：** [v4 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)

---

## 1. 文档目的

本说明只定义 `v5 / Phase 2B` 相对 `v4` 交互基线的增量，不重写整份页面全景。

它解决的问题是：

- 哪些页面需要新增或强化能力
- 新能力如何保持 `Phase 2A` 的 trust model 不变
- 用户如何感知新的 scope、evidence、retrieval explain 和 history

---

## 2. 不变前提

以下基线在 `Phase 2B` 不改变：

- 页面结构仍以 `index.html / search.html / detail.html / ops.html` 为主
- `answered / needs_scope / refused / failed` 的结果语义不变
- 无 citation 不得当作正常回答展示
- freshness、拒答与 release-readiness 继续作为正式产品边界

---

## 3. 新增核心对象

### `scope_filter_set`

用于表达 `标签 / 来源类型 / 时间范围` 的显式过滤条件。

用户可感知字段至少包括：

- `tags`
- `source_types`
- `time_range`
- `scope_summary`

### `history_entry`

用于在最近问题列表中展示可回看的旧 session。

用户可感知字段至少包括：

- `session_id`
- `question`
- `answer_status`
- `scope_summary`
- `updated_at`

### `claim_group`

用于把答案中的关键结论与 supporting citations 组织在一起。

用户可感知字段至少包括：

- `claim_summary`
- `supporting_citations`
- `freshness_badge`

### `excluded_reason`

用于说明某条检索结果为什么没有进入最终答案。

首版至少需要支持用户可理解的原因分类，而不是只保留内部日志。

---

## 4. 页面增量

### 4.1 `index.html` 问答工作台

#### 新增 / 强化模块

1. 扩展范围控制条
2. 按 claim 组织的 evidence package
3. 最近问题列表
4. `继续提问` 入口

#### 交互要求

- 问题输入区除了 `全库 / 当前搜索结果 / 单篇文档`，还要支持 `标签 / 来源类型 / 时间范围`
- 当前 scope 必须在提问前、生成中和结果态都保持可见
- 答案卡片中的 citation 展示改为“claim + supporting citations”的结构，而不是只平铺片段
- freshness 风险应出现在 claim 或证据层，而不是只藏在底部详情
- 最近问题列表至少展示问题、结果状态、scope 摘要和时间
- 用户从最近问题进入 `继续提问` 时，页面必须明确告诉他继承了什么 scope

#### 明确禁止

- scope 改变后沿用旧答案
- 把“继续提问”伪装成没有边界的聊天线程
- 在用户不可见的情况下自动扩 scope

### 4.2 `search.html` 检索实验台

#### 新增 / 强化模块

1. contribution 解释区
2. excluded result 列表
3. URL state 增强

#### 交互要求

- 结果卡片需要明确区分 lexical / semantic / rerank 的贡献
- 对于进入答案的结果，要标出“已进入 citation”
- 对于未进入答案的结果，要给出用户可理解的 excluded reason
- 问题、scope、来源类型、标签、时间范围和主要诊断筛选都应进入 URL
- 问答页应能直接跳转到与当前 session 对应的 search explain 视图

#### 明确禁止

- 独立于问答页 facts 的第二套 explain 数据
- 暴露模型 prompt、chain-of-thought 或内部调试日志

### 4.3 `detail.html` 证据与引用详情页

#### 新增 / 强化模块

1. claim group 对应的引用视图
2. freshness 强化提示
3. “被哪次问题引用”上下文

#### 交互要求

- 用户在详情页能够看清一个 claim group 对应了哪些 citation
- citation 仍然要保留文档、片段和 locator 级定位
- freshness 状态应与问答页和 search 页保持一致

### 4.4 `ops.html` 评估与运维板

`ops.html` 在 `Phase 2B` 仍属于次级增强，不是首个主入口。

#### 目标

- 产品、工程和运维快速判断问题主要集中在 `scope / retrieval / evidence / provider / freshness` 哪一层

#### 交互要求

- 展示 recall、groundedness、citation coverage、latency、cost、backlog 等趋势
- 展示最近失败会话聚类，而不是单条日志堆叠
- 指标名和口径必须与 `Phase 2A Evaluation Plan` 一致

---

## 5. 关键状态规则

1. 任何 scope 变更都会让当前答案失效，并回到待生成状态。
2. `继续提问` 必须创建新的 `session_id`，旧 session 只读保留。
3. 历史列表服务于“回看”和“继续”，不代表系统拥有长期记忆。
4. search explain、detail citation 和 answer evidence package 必须共享同一条事实链。
5. 当资料 stale、索引失败或证据不足时，页面必须继续优先展示边界和补救动作，而不是模糊生成。

---

## 6. 本轮明确不纳入交互 delta 的内容

- 多轮 assistant 对话气泡体系
- 团队共享线程、评论和协作标注
- 摘要、推荐和高级过滤器重构
- 复杂 evidence tree 编辑
- provider 级调试台
