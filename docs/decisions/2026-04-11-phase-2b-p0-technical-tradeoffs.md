# Phase 2B P0 Technical Tradeoffs

**日期：** 2026-04-11
**版本：** `v5 / Phase 2B`
**状态：** draft
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
- [Phase 2B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
- [v5 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
- [v5 Handoff](/Users/coderlauu/xRag/docs/handoff/v5.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 1. 本文档目的

这份文档用于在进入 `Phase 2B` 技术方案评估前，先把最关键的方向性 tradeoff 收敛到可以继续冻结的范围。

它不回答“具体代码怎么写”，而是先回答：

- 新增 scope、history、evidence 和 retrieval explain 是否要重写已有 trust model
- 哪些边界可以直接继承 `Phase 2A`
- 哪些问题要在后续 contract freeze 前补齐

---

## 2. 当前工程事实

基于当前 repo 和最新 main 基线，`Phase 2B` 的 tradeoff 必须建立在以下事实之上：

1. `Phase 2A` 已完成可信问答闭环，最新主线 GitHub Actions run `24272717403` 已通过 `validate / integration / e2e / deploy-production / smoke-production`。
2. 当前产品已存在 answer session、citation、retrieval resource、freshness 和拒答语义，不能为了新体验重新定义这些状态。
3. 现有 scope 以 `全库 / 当前搜索结果 / 单篇文档` 为主，其中 `search_result` 已采用 snapshot 语义。
4. 当前系统仍是单用户私有知识库，不存在团队协作、共享线程或权限模型。
5. production 继续具备 `db.xrag.coderlau.cn` 的 pgweb 管理台，以及 PostgreSQL `127.0.0.1:5432` 回环映射，可用于后续数据模型排查。

---

## 3. 已收敛方向

### 3.1 扩展 scope 必须建立在显式 typed contract 上

**结论**

- `标签 / 来源类型 / 时间范围` 作为对现有 scope 的增量扩展，不另起一套隐藏过滤体系。
- 首版只接受用户可感知的 inclusion 型过滤，不引入复杂布尔组合器。
- 新 scope 仍然必须在 session 中留下稳定的 `scope_summary`。

**原因**

- `Phase 2B` 的用户价值核心是“更可控”，不是“更隐式地帮你猜范围”。
- 若把高级过滤藏到内部参数里，后续 history、evidence 和 retrieval explain 都会失去一致事实源。

**实施约束**

- 任何 follow-up 或 history reopen 都不得绕过 scope 可见性。
- `search_result` snapshot 语义不能被新过滤模型替代。

### 3.2 `继续提问` 采用“新 session，显式继承”而不是“多轮 memory”

**结论**

- `继续提问` 必须创建新的 answer session。
- 新 session 只允许显式继承上一条 session 的 scope 或用户明确可见的上下文摘要。
- 旧 session 保持只读，不在原地改写。

**原因**

- 当前 trust model 建立在单次问题、单次 scope、单次 citation 的可核查闭环上。
- 一旦把历史入口做成隐式 memory，`refused / needs_scope / failed` 的语义会立刻混乱。

**实施约束**

- 继续提问不得自动扩 scope。
- 未经用户确认，不得把上一条答案内容当作新的隐藏 prompt 上下文。

### 3.3 retrieval explain 和 evidence package 必须共用同一条事实链

**结论**

- 问答页、检索实验台和详情页都必须读取同一套 retrieval / citation 事实源。
- 不允许为 explain 页面单独 rerun 一套“更好看”的解释链。

**原因**

- `Phase 2B` 的价值是把可信链路讲清楚，而不是生成一套和真实回答不一致的“解释故事”。
- 如果 explain 页面与 answer 页面不一致，用户会直接失去信任。

**实施约束**

- excluded reason 必须来自稳定、可复现的正式分类，而不是临时日志字符串。
- detail 页中的 citation locator 仍然要能回跳到 document / chunk 层。

### 3.4 证据增强只做到“结构化 evidence”，不暴露 chain-of-thought

**结论**

- `claim grouping + 多引用组合 + freshness 强化提示` 是 `Phase 2B` 的证据增强上限。
- 不暴露模型 prompt、chain-of-thought 或自由文本推理轨迹。

**原因**

- 用户需要的是更可核查的 evidence package，不是看模型的内部思维过程。
- 暴露 chain-of-thought 既不稳定，也会把产品边界扩到不可控区域。

**实施约束**

- 所有 claim group 都必须回到 citation。
- 若 claim 无法被 citation 支撑，不能只用“推理解释”补位。

### 3.5 `ops` 面板是正式版本资产，但排序晚于主问答增强

**结论**

- `ops` 面板增强保留在 `Phase 2B` 版本边界内，但排序为 `P1-01`。
- 若排序冲突，优先完成 `scope / evidence / retrieval explain / history` 四项主链。

**原因**

- `ops` 面板主要服务内部诊断和版本守护，不是判断用户价值跃迁是否成立的第一入口。
- 过早把主资源拉去做趋势面板，容易打断本轮产品重心。

**实施约束**

- 指标定义必须沿用 [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)。
- 不允许额外引入第二套产品指标口径。

---

## 4. 明确排除项

本次 tradeoff closure 同时明确排除以下方向进入 `P0`：

- 多轮 assistant memory
- 自动扩 scope 或隐式补资料
- 独立 explain pipeline
- prompt / chain-of-thought 直出
- 搜索高级过滤器重构
- 新的多模型路由平台
- 团队协作、共享线程和权限系统

---

## 5. 进入后续技术评估前必须回答的问题

1. 扩展 scope 的正式 contract 形状是什么，是否需要 selection 上限或固定 time range 语义？
2. `excluded reason` 的最小用户可见分类应如何定义，才能既稳定又不泄露内部实现噪音？
3. `history reopen` 和 `continue asking` 分别读写哪些字段，哪些字段禁止隐式继承？
4. `claim group` 与 citation locator 的 contract 应该长什么样，才能兼顾可读性和回跳能力？
5. `ops` 面板的趋势数据来自哪里，如何保证与 eval contract 同源而不重复造数？

这些问题在 contract freeze 前必须回答，但它们已经不再属于方向选择，而属于实现边界冻结。

---

## 6. 对后续实现的约束

1. 不得重命名或合并现有 `answered / needs_scope / refused / failed` 状态语义。
2. 不得让 history 或 follow-up 破坏单次 session 的 citation 可核查性。
3. 不得让新的 scope 体系绕开现有 snapshot 和可见性规则。
4. 不得让 explain 页面和 answer 页面出现两套事实。
5. 不得用新增体验掩盖 `citation / refusal / freshness / release-readiness` 的回退。
