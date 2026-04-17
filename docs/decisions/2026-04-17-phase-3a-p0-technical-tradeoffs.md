# Phase 3A P0 Technical Tradeoffs

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** draft
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
- [Phase 3A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
- [v7 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md)
- [v7 Handoff](/Users/coderlauu/xRag/docs/handoff/v7.md)
- [v6 Handoff](/Users/coderlauu/xRag/docs/handoff/v6.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)

---

## 1. 本文档目的

这份文档用于在进入 `Phase 3A` 技术评估前，先把最关键的方向性取舍收敛到可以继续冻结的范围。

它不回答“具体表结构和 API 怎么改”，而是先回答：

- `Phase 3A` 是否应该优先做样本级诊断工作流，而不是继续扩聚合指标
- `drill-down / replay` 是否允许建立第二套影子 explain 数据
- 本轮增强做到“看清根因”为止，还是默认带入自动 remediation 或协作流程

---

## 2. 当前工程事实

基于当前仓库和最新主线基线，`Phase 3A` 的 tradeoff 必须建立在以下事实之上：

1. `Phase 2C` 已完成正式收口，并已归档；当前 `ops` 主板已经能稳定回答“哪里不健康”。
2. GitHub Actions run `24543526168` 已成功，`v6` 的 closeout baseline 可作为 `v7` 启动前的最近 main 绿态。
3. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md) 已明确质量指标定义，当前没有理由再造第二套质量口径。
4. 真实运行中已经保留 `db.xrag.coderlau.cn` 的 pgweb 管理台、PostgreSQL `127.0.0.1:5432` 回环映射、CI artifacts 与 inspection guide，可作为后续诊断链路的现实依赖。
5. `Phase 2C` 已把 notices 刻意做成 prompt-only，说明内部治理增强不能随意污染终端用户主流程。

---

## 3. 已收敛方向

### 3.1 `Phase 3A` 以样本级诊断工作流为核心，不继续扩聚合主板

**结论**

- 本轮主入口是从 `ops` 聚合块进入样本与回放，而不是继续优先扩更多 summary cards。
- 聚合主板继续存在，但只作为诊断入口，不作为版本中心能力本身。

**原因**

- `Phase 2C` 已经解决了“看见问题”的层面，当前真正缺的是“追到根因”的层面。
- 若继续扩更多聚合卡片，团队仍会卡在“知道有问题但不知道先看哪一个样本”。

**实施约束**

- `ops` 深钻能力必须围绕 session、document、deployment 三类对象组织，而不是先造新的 dashboard 组件层。
- 不得让 `Phase 3A` 重新回到“只有聚合数字、没有样本入口”的状态。

### 3.2 诊断工作流继续复用真实事实源，不新造影子 explain pipeline

**结论**

- `session replay`、`document replay`、`deployment compare` 必须复用现有 facts 或其正式扩展。
- 不允许为页面展示单独造一套只读解释数据。

**原因**

- 如果诊断页与真实 answer、citation、indexing、deployment facts 分叉，产品会迅速失去可信度。
- `Phase 2A / 2B / 2C` 已花了很大代价冻结 trust boundary，`Phase 3A` 不能因为诊断便利而重新破坏它。

**实施约束**

- 后续技术评估必须回答每个回放视图到底来自哪条正式事实链。
- 若某个回放字段无法从现有 facts 稳定导出，必须先说明是正式扩展还是降 scope，而不是默默拼接临时解释。

### 3.3 `Ask / Search / Detail` 继续只暴露最小入口，不承担诊断主流程

**结论**

- 内部诊断主入口仍在 `ops` 资源族下。
- `Ask / Search / Detail` 最多保留轻量跳转或上下文提示。

**原因**

- 终端用户的主问题仍是“能不能得到可信答案”，不是“能不能看到所有内部诊断对象”。
- 若把内部诊断工作流塞回主问答链，会破坏 `Phase 2A / 2B` 已建立的用户心智。

**实施约束**

- 任何新增内部状态都不得重命名或改写终端问答状态语义。
- session replay、document replay、deployment compare 默认不直接出现在终端主路径里。

### 3.4 `P0` 先做到“定位根因”，不做到“自动处置”

**结论**

- `Phase 3A / P0` 先回答“问题在哪里、为什么发生、先看哪个样本”。
- 自动 rerun、自动回滚、自动重建索引全部延后。

**原因**

- 当前最大问题是人工调查路径太长，不是完全没有处置动作。
- 自动处置会立刻把版本拉向执行编排、权限和审计，超出本轮主线。

**实施约束**

- 推荐动作可以存在，但只能服务于人工判断，不变成自动执行器。
- 运维入口联动若进入 `P1`，也只能是桥接现有入口，不是隐式自动化。

### 3.5 成本与质量联动继续作为 `P1`，不提前打开多模型治理平台

**结论**

- `P1-01` 保留在后续补强，不进入 `P0`。
- `Phase 3A` 的主线仍是样本级诊断，不是 provider / model / route 的治理平台。

**原因**

- 当前多模型治理边界尚未正式提出，若现在就做成本联动，很容易把版本重新拉向 platform 化。
- 样本级诊断与 provider/model 维度分析虽然相关，但不是同一个最小闭环。

**实施约束**

- 若技术评估中发现 `P0` 需要依赖 provider / model 新 contract，优先降 scope，而不是顺手扩平台。

---

## 4. 明确排除项

本次 tradeoff closure 同时明确排除以下方向进入 `P0`：

- 自动修复、自动回滚、自动重建索引
- 自由文本日志搜索台
- 独立于现有 facts 的第二套 explain pipeline
- 在线评测平台、golden set 编辑器
- 多模型路由平台
- 团队协作、权限系统、通知编排

---

## 5. 进入后续技术评估前必须回答的问题

1. `diagnostic_sample` 的最小稳定主键是什么，如何让 trend、incident、deployment 三类入口共享同一对象标识？
2. `answer session replay` 的最小字段集是什么，哪些字段来自现有 facts，哪些属于正式扩展？
3. `document / indexing replay` 如何复用现有 documents / indexing 状态，而不是重造 ingest timeline？
4. 发布窗口 compare 最小时间窗口和基线如何定义，才能稳定表达“新回归”而不是噪音波动？
5. `ops` 下的新深钻资源应扩展现有 `/ops/*` 资源还是新增分层资源，如何避免同一事实被多处重复计算？

这些问题在 contract freeze 前必须回答，但它们已经不再属于方向选择，而属于实现边界冻结。

---

## 6. 对后续实现的约束

1. 不得重命名或改写 `Phase 2A / 2B / 2C` 已冻结的问答状态语义。
2. 不得为了 `replay` 能力重造一套 answer、citation、retrieval 或 indexing 事实链。
3. 不得让诊断页面口径与 `Phase 2A Evaluation Plan` 分叉。
4. 不得把自动 remediation 伪装成只是“推荐动作”的小改动混入 `P0`。
5. 不得让 `Phase 3A` 的内部诊断增强破坏主问答页面的简洁性和可信边界。
