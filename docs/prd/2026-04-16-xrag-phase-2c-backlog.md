# xRag Phase 2C Product Backlog

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** active
**对应 PRD：** [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
**对应交互：** [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
**对应技术取舍：** [Phase 2C P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)

---

## 1. 文档目的

这份文档把 `Phase 2C PRD` 继续收敛成可以进入技术评估的 backlog，用于：

- 明确 `ops / eval / release-readiness` 哪些能力值得进入 `P0`
- 避免把运行态问题、自动化幻想和内部工具欲望混成一个版本
- 给后续 interaction、tradeoff、technical evaluation 提供明确验收清单

当前阶段仍然只冻结产品边界，不在这里承诺最终实现方案。

---

## 2. 排序原则

1. 先解决“当前能不能答、为什么不能答”，再补更深的诊断细节
2. 治理面板只能沿用正式质量口径，不能另造指标系统
3. 优先做聚合判断和处置优先级，不把 `P0` 变成日志浏览器
4. `ops` 面板是内部治理入口，不得反向绑架主问答页面
5. 自动修复、自动回滚、自动调度一律不进入 `P0`

---

## 3. P0 Backlog

### `P0-01` 语料就绪度面板

**用户价值**

- 值班者能快速判断“为什么现在 Ask 不能稳定 retrieval”，避免把 corpus readiness 问题误诊为模型或前端问题。

**范围内**

- `ready / queued / chunking / embedding / stale / failed` 分层可视
- readiness rate
- freshness lag、embedding backlog
- “当前 Ask 是否受语料就绪度阻断”的显式提示
- 与现有 answer summary 的统一摘要口径

**明确不含**

- 一键重建索引
- 自动回补历史文档
- 文档级批量操作台
- 新的 ingest 工作流编排

**验收清单**

- [ ] 值班者能一眼看出当前是“资料不够可问”还是“资料已就绪但别处有问题”
- [ ] `not_indexed / backlog / stale / failed` 不再只是排障知识，必须有正式 UI 语义
- [ ] readiness 结论与 Ask 真实行为保持一致，不出现“面板说可问，Ask 实际拿不到证据”

**前置依赖**

- 现有 `ops answer summary`
- `documents / indexing` 状态事实源

### `P0-02` 质量评分卡与趋势

**用户价值**

- 产品和工程可以判断这次迭代或发布是否让可信问答回退，而不是只看 smoke 是否通过。

**范围内**

- `groundedness`
- `citation_coverage`
- `refusal_precision`
- `latency_p50 / latency_p95`
- `avg_token_cost_usd`
- 时间窗口趋势
- “较上一稳定窗口是否明显退化”的可见判断

**明确不含**

- 新的离线评测平台
- 在线 rubric 编辑器
- 模型 prompt 级调试台
- 跨模型自动对比实验平台

**验收清单**

- [ ] 指标名称和定义与 `Phase 2A Evaluation Plan` 完全一致
- [ ] 团队可以从面板直接发现明显的质量或成本退化
- [ ] 趋势视图不会与主问答页面的质量事实相冲突

**前置依赖**

- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- 现有 answer session 与质量摘要事实源

### `P0-03` Incident 聚类与推荐动作

**用户价值**

- 值班者不需要先看满屏 incident 再人工总结，系统应先给出“问题主要在哪一层”的聚类视图。

**范围内**

- 按 `source` 聚类
- 按 `severity` 聚类
- 最近失败集中类型
- 推荐动作与优先级
- 与 release / health / readiness 的关联提示

**明确不含**

- 自由文本日志搜索台
- 自动生成复盘
- 自动修复或自动 rerun
- 复杂规则引擎

**验收清单**

- [ ] 值班者可以先看聚类，再决定是否深入具体日志
- [ ] 推荐动作能覆盖常见高风险场景，而不是只给出空泛提示
- [ ] 同类 incident 不再只能以平铺列表出现

**前置依赖**

- 现有 incident 列表与 severity/source 分类

### `P0-04` 发布关联与健康守门

**用户价值**

- 发布后能更快判断“问题是最近部署引入的，还是长期积累的运行债务”。

**范围内**

- 最新 deployment 摘要
- smoke 结果
- 核心服务健康
- 与最近质量漂移、incident 扩散、readiness 下降的同屏对照
- 稳定版本基线提示

**明确不含**

- 自动回滚
- 多环境发布编排
- 灰度流量控制台
- 基础设施级监控替代

**验收清单**

- [ ] 值班者能在一个页面判断“刚发版后是否安全”
- [ ] smoke、健康检查和质量趋势能一起读，而不是三处来回跳
- [ ] 页面能清楚区分“新回归”和“旧积压问题”

**前置依赖**

- 现有 deployment、health、incident 事实源

---

## 4. P1 Backlog

### `P1-01` Drill-down 与样本回放

- 目标：从趋势或 incident 进入单个样本、单次会话、单次检索 / 索引链路，帮助做根因判断
- 进入条件：`P0-01 ~ P0-04` 已冻结且聚合面板成立
- 验收重点：drill-down 必须复用真实事实源，不允许新造只读 mock 数据

### `P1-02` 成本与质量联动分析

- 目标：按 provider / model / route 看质量和成本权衡
- 进入条件：多模型治理边界在后续版本被正式提出
- 验收重点：不得为了这项能力提前打开新的多模型平台范围

---

## 5. Deferred

- 自动 remediation、自动 rerun、自动回滚
- 在线 golden set 编辑与标注系统
- 告警订阅与通知编排
- 团队协作型值班看板
- 新的 Ask 主交互重构
- 摘要、推荐、多模型路由、联网回答

这些能力统一视为后续候选，不得在 `Phase 2C` 技术评估里默认带入。

---

## 6. 阶段顺序

1. `Core-A`: `P0-01 + P0-02`
   目标：先把“是否可问”和“质量是否退化”做成统一质量主板
2. `Core-B`: `P0-03 + P0-04`
   目标：再把 incident 治理和发布判断串成操作闭环
3. `Internal-Enhancement`: `P1-01 + P1-02`
   目标：在主板稳定后，再做细粒度 drill-down 和成本联动

---

## 7. 进入技术评估前的产品检查项

- [ ] `P0-01 ~ P0-04` 的范围内 / 范围外已经明确
- [ ] `Phase 2A Evaluation Plan` 已被确认为唯一指标口径
- [ ] `corpus readiness` 已被提升为正式产品事实，而不是运维 side note
- [ ] `incident` 聚类和推荐动作至少有最小稳定分类，不依赖临时日志文本
- [ ] `P1` 与 `deferred` 没有重新包装后混入 `P0`
