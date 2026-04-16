# Phase 2C P0 Technical Tradeoffs

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** draft
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
- [v6 Handoff](/Users/coderlauu/xRag/docs/handoff/v6.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)

---

## 1. 本文档目的

这份文档用于在进入 `Phase 2C` 技术评估前，先把最关键的方向性取舍收敛到可以继续冻结的范围。

它不回答“具体表结构和 API 怎么改”，而是先回答：

- `Phase 2C` 是否应该作为独立治理面板版本，而不是继续扩终端问答入口
- 质量、readiness、incident、deployment 之间是否允许各自维护独立口径
- 本轮增强做到“可见与可治理”为止，还是默认带入自动 remediation

---

## 2. 当前工程事实

基于当前仓库和最新主线基线，`Phase 2C` 的 tradeoff 必须建立在以下事实之上：

1. `Phase 2B` 已完成正式收口，并已归档；`Ask / Search / Detail` 的可信问答主链已经成立。
2. 当前 `ops` 页面已经具备 `answer summary / health summary / incidents / latest deployment` 的最小数据基线，但还不是完整治理主板。
3. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md) 已明确 `retrieval / answer / runtime` 的指标定义，当前没有理由再造第二套指标系统。
4. 真实运行中已经出现“文档处于 not_indexed，Ask 无法 retrieval”的问题，说明 corpus readiness 必须作为正式质量门槛，而不是实现细节。
5. 生产环境仍保留 `db.xrag.coderlau.cn` 的 pgweb 管理台和 PostgreSQL `127.0.0.1:5432` 回环映射，可用于后续技术评估阶段核对运行数据模型。

---

## 3. 已收敛方向

### 3.1 `Phase 2C` 以内部治理主板为核心，不重写主问答入口

**结论**

- 本轮主入口是强化后的 `ops.html`，不是重新改造 `Ask / Search / Detail` 为治理中心。
- `Ask / Search / Detail` 只暴露最小必要的质量或 readiness 提示。

**原因**

- 当前主链用户价值已经在 `Phase 2B` 收口；再把治理逻辑塞回主问答入口，会同时损害终端体验和版本聚焦。
- `Phase 2C` 的主用户是产品、工程和运维，而不是纯消费问答结果的终端用户。

**实施约束**

- 任何新增治理能力都不得反向定义新的终端问答状态机。
- `ops` 页面可以成为事实主板，但不能成为第二套问答解释系统。

### 3.2 质量指标继续沿用 `Phase 2A Evaluation Plan`，不新造口径

**结论**

- `groundedness / citation_coverage / refusal_precision / latency / cost / backlog / freshness_lag` 继续沿用现有定义。
- `Phase 2C` 允许扩展展示方式，不允许修改指标语义。

**原因**

- 当前仓库已经有正式评估计划，再造口径只会让产品、工程和运维失去共同语言。
- 如果趋势面板和离线评估基线不一致，`ops` 页面会迅速失去可信度。

**实施约束**

- 若需要新增字段，必须明确它是“展示补充字段”还是“新指标”，不能混写。
- 后续技术评估必须回答每个指标来自哪条正式事实链。

### 3.3 `corpus readiness` 必须提升为一等公民，而不是隐藏排障知识

**结论**

- `ready / backlog / stale / failed / freshness lag / blocking_reason` 必须成为正式产品事实。
- readiness 不只是“索引系统内部状态”，而是影响 Ask 是否可用的用户可解释门槛。

**原因**

- 近期真实问题已经证明：即使代码和页面都看起来正常，没有 ready corpus 也无法给出可信答案。
- 若继续把 readiness 当成运维 side note，团队会持续误判故障层级。

**实施约束**

- readiness 结论必须与 Ask 真实行为保持一致。
- 不允许把“面板说可问、实际 retrieval 拿不到证据”的矛盾留到后期再解释。

### 3.4 incident 聚类优先于日志浏览和自动修复

**结论**

- `Phase 2C` 先做来源、严重度、影响面的稳定聚类与推荐动作。
- 自动 remediation、自动 rerun、自由文本日志控制台全部延后。

**原因**

- 当前最大问题不是“完全看不到日志”，而是“看到太多原始信号却不知道先处理什么”。
- 自动修复会直接把版本拉向执行编排和风险控制，超出当前主线。

**实施约束**

- incident 分类必须建立在稳定字段上，而不是日志全文匹配。
- 推荐动作可以基于规则，但不能伪装成确定性自动处置。

### 3.5 发布守门只做到“关联判断”，不做到“自动处置”

**结论**

- 最新 deployment、smoke、服务健康和质量漂移需要共屏判断。
- 但 `Phase 2C` 不自动回滚、不自动暂停入口、不自动重建索引。

**原因**

- 当前版本目标是提升运行判断质量，而不是引入新的自动化风险面。
- 自动处置需要更严格的回滚策略、权限模型和执行审计，不适合在本轮顺带带入。

**实施约束**

- 任何发布关联结论都必须保留人工判断空间。
- 若需要后续自动化，必须在新版本中单独立项。

---

## 4. 明确排除项

本次 tradeoff closure 同时明确排除以下方向进入 `P0`：

- 自动修复、自动回滚、自动重建索引
- 新的终端问答体验主线
- 独立于现有 facts 的第二套指标与 explain pipeline
- 在线评测平台、golden set 编辑器
- 多模型路由平台
- 团队协作、权限系统、通知编排

---

## 5. 进入后续技术评估前必须回答的问题

1. `corpus readiness` 的正式 contract 是页面实时汇总、预聚合快照，还是两者结合？
2. 质量趋势最小时间窗口和对比基线该如何定义，才能稳定表达“退化”而不是噪音波动？
3. incident 聚类最小稳定主键是什么，哪些维度允许聚合，哪些必须保留 drill-down？
4. `ops` 页面应扩展现有 `/ops/*` 资源还是新增分层资源，如何避免同一事实被多处重复计算？
5. 哪些治理状态需要向 `Ask / Search / Detail` 提供轻量提示，哪些必须留在内部面板，避免污染终端体验？

这些问题在 contract freeze 前必须回答，但它们已经不再属于方向选择，而属于实现边界冻结。

---

## 6. 对后续实现的约束

1. 不得重命名或改写 `Phase 2A / 2B` 已冻结的问答状态语义。
2. 不得为了 `ops` 增强重造一套 answer、citation 或 retrieval 事实链。
3. 不得让治理页面口径与 `Phase 2A Evaluation Plan` 分叉。
4. 不得把自动 remediation 伪装成只是“推荐动作”的小改动混入 `P0`。
5. 不得让 `Phase 2C` 的内部治理增强破坏主问答页面的简洁性和可信边界。
