# xRag Phase 2B Product Backlog

**日期：** 2026-04-11
**版本：** `v5 / Phase 2B`
**状态：** active
**对应 PRD：** [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
**对应交互：** [v5 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
**对应技术取舍：** [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)

---

## 1. 文档目的

这份文档把 `Phase 2B PRD` 中的 `P0 / P1 / deferred` 继续细化成可执行 backlog，用于：

- 把 `Phase 2A` 的 `P1` 候选正式收敛为新的版本范围
- 约束技术方案只围绕本轮真实主线展开
- 为后续 interaction、tradeoff、contract freeze 和 implementation lanes 提供产品验收清单

当前阶段仍处于产品与技术边界冻结，不在这里定义正式实现方案。

---

## 2. 排序原则

1. 先提升用户可控性与可解释性，再补内部可视化面板
2. 所有新增体验都必须建立在 `Phase 2A` 既有信任边界之上
3. 同一条问答的 scope、retrieval、citation、history 必须共享同一份事实源
4. `继续提问` 不得演化成隐式 memory 或自动扩 scope
5. `P1` 和 `deferred` 不得反向挤占 `P0` 的设计空间

---

## 3. P0 Backlog

### `P0-01` 扩展范围控制

**用户价值**

- 用户能真正控制“答案来自哪组资料”，而不是只能在很粗粒度的范围上做取舍。

**范围内**

- `标签` 范围
- `来源类型` 范围
- `时间范围`
- 当前 question/session 的 scope 摘要与可见标签
- scope 变更后的明确失效与重问行为

**明确不含**

- 复杂布尔表达式过滤器
- 多段时间窗口组合
- 保存筛选模板
- 隐式继承搜索页中用户看不见的过滤器

**验收清单**

- [ ] 用户在提问前或提问时可以清楚看到当前 scope
- [ ] `标签 / 来源类型 / 时间范围` 会作为正式产品事实展示，而不是隐藏实现参数
- [ ] 切换 scope 后，旧答案不会被当成新范围下的结果继续展示
- [ ] 当 scope 仍然过大或冲突明显时，系统可以回到 `needs_scope`

**前置依赖**

- `Phase 2A` 已有的 `全库 / 当前搜索结果 / 单篇文档` 范围模型

### `P0-02` 检索实验台基线

**用户价值**

- 用户、产品和工程都能看懂一次问答为何召回这些文档、为何排除其他文档。

**范围内**

- lexical / semantic / rerank 贡献展示
- 被召回但未进入答案的结果列表
- 未入答原因
- URL state，可复现当前问题与筛选条件
- 从问答结果跳转到检索实验台的入口

**明确不含**

- prompt 原文或 chain-of-thought 暴露
- 独立于主问答事实源的第二套 explain pipeline
- A/B 策略平台
- provider 级调试日志直出

**验收清单**

- [ ] 用户能看见哪些文档进入了最终答案，哪些没有进入
- [ ] 未入答结果至少有用户可理解的原因分类，而不是只剩内部日志
- [ ] 检索实验台展示的结果与问答页的 citation / retrieval facts 一致
- [ ] URL 可以稳定复现当前问题、scope 和主要筛选条件

**前置依赖**

- `Phase 2A` 的 retrieval trace 资源与 citation 事实源

### `P0-03` 证据包增强

**用户价值**

- 用户可以更快读懂“答案里每条关键 claim 由哪些证据支持”，而不是在平铺 citation 里自己拼装。

**范围内**

- claim grouping
- 多引用组合
- freshness 风险提示强化
- citation 数量与关键证据摘要

**明确不含**

- chain-of-thought 展示
- 自由文本推理轨迹
- 跨文档 diff viewer
- 高复杂度证据树编辑能力

**验收清单**

- [ ] 每个 `answered` 结果仍然保持 citation 可核查
- [ ] 用户可以按 claim 理解证据，而不是只看到平铺片段
- [ ] freshness 风险在证据包中可见，不会被埋在底部细节
- [ ] 增强后的证据包不会降低首屏可读性

**前置依赖**

- `Phase 2A` 的 citation 与详情页定位能力

### `P0-04` 问题历史与继续提问

**用户价值**

- 用户完成一条问答后，可以稳定回到旧结果，或从已验证过的上下文继续提问，而不是重新搭一遍问题。

**范围内**

- 最近问题列表
- 旧 session 回看
- 继续提问入口
- 继续提问时的显式 scope 继承或修改

**明确不含**

- 多轮 assistant memory
- 会话重命名、收藏、文件夹管理
- 团队共享线程
- 自动基于旧答案扩 scope 或补资料

**验收清单**

- [ ] 用户能看到最近问题、结果状态和对应 scope 摘要
- [ ] 回看旧 session 时，旧答案保持只读，不会被新问题覆盖
- [ ] `继续提问` 会创建新的 session，而不是在旧 session 上隐式改写
- [ ] 新 session 继承了什么上下文，用户是明确可见的

**前置依赖**

- `Phase 2A` 的 answer session 基线

---

## 4. P1 Backlog

### `P1-01` 评估与运维面板增强

- 目标：把 recall、groundedness、citation coverage、latency、cost、backlog 等趋势系统产品化
- 进入条件：`P0-01 ~ P0-04` 已冻结且不会被面板需求反向牵引
- 验收重点：指标定义必须与 `Phase 2A Evaluation Plan` 一致，不得出现第二套产品口径

---

## 5. Deferred

- 单文档摘要、多文档对比摘要
- 相关内容推荐
- 搜索高级过滤器重构
- 多模型路由与更细粒度模型策略
- 多轮 assistant memory / 自动扩 scope
- 开放互联网联网回答
- 浏览器插件
- 团队协作 / 权限模型
- 移动端

这些能力统一视为后续候选项，不得在 `Phase 2B` 的技术方案设计中默认带入。

---

## 6. 阶段顺序

1. `Core-A`: `P0-01 + P0-03`
   目标：先把范围和证据包这两个最直接影响“可信感知”的能力做实
2. `Core-B`: `P0-02 + P0-04`
   目标：再补检索解释与历史工作流，让问答可回放、可延续
3. `Internal-Enhancement`: `P1-01`
   目标：把内部质量守护与诊断能力产品化，但不打断主用户心智

---

## 7. 进入技术方案评估前的产品检查项

- [ ] `P0-01 ~ P0-04` 的范围内 / 范围外已经明确
- [ ] `P1-01` 没有反向改写主问答入口的用户心智
- [ ] `continue asking` 已明确不是多轮 memory assistant
- [ ] `excluded reason` 和 claim grouping 至少有最小用户可见口径
- [ ] `deferred` 能力没有被重新包装后混入 `P0`
