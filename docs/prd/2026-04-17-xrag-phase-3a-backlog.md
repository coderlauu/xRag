# xRag Phase 3A Product Backlog

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** active
**对应 PRD：** [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
**对应交互：** [v7 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md)
**对应技术取舍：** [Phase 3A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-p0-technical-tradeoffs.md)

---

## 1. 文档目的

这份文档把 `Phase 3A PRD` 继续收敛成可以进入技术评估的 backlog，用于：

- 明确 `drill-down / replay / release diff` 哪些能力值得进入 `P0`
- 避免把自动 remediation、协作工具和多模型治理混成一个版本
- 给后续 interaction、tradeoff、technical evaluation 提供明确验收清单

当前阶段仍然只冻结产品边界，不在这里承诺最终实现方案。

---

## 2. 排序原则

1. 先解决“如何快速追到根因”，再补更深的分析增强
2. 诊断工作流只能沿用正式事实源，不能另造影子 explain 数据
3. 优先做样本定位和回放，不把 `P0` 变成日志浏览平台
4. `ops` 深钻是内部诊断入口，不得反向绑架主问答页面
5. 自动修复、自动回滚、自动调度一律不进入 `P0`
6. 异步 active 状态必须有服务端终态收口和前端轮询兜底，不能把“仍在处理”作为无限等待的默认解释

---

## 3. P0 Backlog

### `P0-01` 样本级 drill-down 入口

**用户价值**

- 值班者可以从趋势、incident 或 release guard 直接进入异常样本，而不是重新查 session、数据库和 artifacts。

**范围内**

- 从趋势卡片进入样本列表
- 从 incident cluster 进入样本列表
- 从 release guard 进入部署窗口样本列表
- 保留时间窗口、来源、severity、deployment 上下文

**明确不含**

- 任意日志全文搜索
- 自动筛出“唯一根因”
- 协作评论、分派和处理状态
- 直接执行修复动作

**验收清单**

- [ ] 值班者能从聚合块直接进入具名样本，而不是只看到数量
- [ ] drill-down 页面保留来源和时间窗口，不丢失调查上下文
- [ ] 相同异常样本不会在不同入口显示出相互冲突的状态

**前置依赖**

- `ops` overview / trends
- incident cluster
- deployment_records

### `P0-02` Answer session 回放

**用户价值**

- 工程和产品可以在一个内部视图里复原单次问答到底发生了什么，而不是手工拼 query、retrieval、citation 和 terminal state。

**范围内**

- query 与 session metadata
- scope snapshot
- retrieval items 与 why-not-in-answer
- citations、freshness、refusal / failed 状态
- 时间线与关联 incident / deployment 入口

**明确不含**

- prompt 编辑器
- 重新执行 answer
- 在线改答案或改判分
- chain-of-thought 展示

**验收清单**

- [ ] 团队能从 session replay 直接理解一次失败或低质量回答的关键链路
- [ ] replay 所见字段与正式 answer facts 一致
- [ ] session replay 不会要求新增终端用户状态语义

**前置依赖**

- answer_sessions
- answer_citations
- `Phase 2A / 2B` 已存在的 retrieval / answer facts

### `P0-03` Document / indexing 链路回放

**用户价值**

- 当 readiness 下降时，团队能快速知道是导入底料、处理链路、索引阶段还是 freshness 漂移出的问题。

**范围内**

- document 关键元信息
- `upload / parse / ocr / fetch / projection / indexing / stale / failed` 关键状态
- 与当前 readiness / incident 的关联提示
- 阻断点和最近变化时间

**明确不含**

- 文档批量操作台
- 一键重试或重建索引
- 新的 ingest pipeline 编排
- 存储层运维替代

**验收清单**

- [ ] 团队能区分 backlog、失败和 stale/freshness 漂移
- [ ] 链路回放可直接解释为什么当前 document 影响 Ask
- [ ] 回放口径与 corpus readiness 主板保持一致

**前置依赖**

- documents / indexing 状态事实源
- `Phase 2C` corpus readiness 基线

### `P0-04` 发布窗口对比与受影响样本定位

**用户价值**

- 发布后，团队可以快速回答“是这次 deploy 带来的新回归，还是旧运行债务继续爆出来”。

**范围内**

- 选定 deployment 前后窗口
- 关联质量趋势、incident、样本列表
- 展示当前 deployment、上一个稳定版本与 smoke 结果
- 标识疑似新增异常样本

**明确不含**

- 自动回滚
- 灰度控制台
- 多环境编排平台
- 基础设施级监控替代

**验收清单**

- [ ] 值班者能在发布窗口里定位最值得看的一批样本
- [ ] 部署信息、smoke 结果和质量退化可以一起读
- [ ] 页面能清楚区分“新回归”和“旧积压问题”

**前置依赖**

- deployment_records
- incident cluster
- quality trends

### `P0-G1` Ask active-session 终态收口与轮询兜底

**用户价值**

- 用户不会因为 answer session 长期停在 `retrieving` 或 `synthesizing` 而在 Ask 页面无限等待；团队也能把 stuck session 纳入诊断事实，而不是靠页面轮询暴露。

**范围内**

- 服务端确保 `idle / retrieving / synthesizing` 不会无限期保持 active
- BullMQ failed / stalled / exhausted retries 与 `answer_sessions` 终态对账
- Worker 处理链路扩大失败保护，进入处理后的不可恢复异常必须尽量写成 `failed`
- Ask 页面增加最大轮询保护和 stuck 提示
- answer replay / ops diagnostic 读取 stuck 或 failed session 时保持事实一致

**明确不含**

- 新增 `AnswerSessionStatus` enum 值
- 把 `stale`、`timed_out` 等新状态写入 session 状态机
- 前端自行伪造服务端 terminal state
- 自动 retry、自动 rerun、自动 reindex 或自动 remediation

**验收清单**

- [ ] active answer session 超过服务端阈值后会被收口到 `failed`
- [ ] BullMQ job failed / stalled / retries exhausted 后不会留下永久 active session
- [ ] Ask 页面不会无限轮询同一个 active session
- [ ] 用户能看到可解释 stuck / failed 提示，并可进入诊断入口
- [ ] 新增测试覆盖服务端终态收口、queue 对账和前端轮询兜底

**前置依赖**

- `AnswerSessionStatus` 既有 enum
- answer session / retrieval trace 既有事实面
- BullMQ answer job 生命周期
- [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

---

## 4. P1 Backlog

### `P1-01` 成本与质量联动分析

- 目标：按 provider / model / route 看质量与成本权衡
- 进入条件：`P0-01 ~ P0-04` 已冻结且样本级工作流成立
- 验收重点：不得为了这项能力提前打开新的多模型平台范围

### `P1-02` 运维入口联动

- 目标：在 drill-down / replay 视图中有组织地链接 pgweb、CI artifacts、inspection guide 等现有入口
- 进入条件：诊断对象和回放视图已成立，且不要求在 `P0` 中直接打通所有环境细节
- 验收重点：链接和 runbook 只是辅助入口，不能替代正式产品事实

---

## 5. Deferred

- 自动 remediation、自动 rerun、自动回滚
- 自由文本日志检索台
- 在线 golden set 编辑与标注系统
- 告警订阅与通知编排
- 团队协作型值班看板
- 新的 Ask 主交互重构
- 摘要、推荐、多模型路由、联网回答

这些能力统一视为后续候选，不得在 `Phase 3A` 技术评估里默认带入。

---

## 6. 阶段顺序

1. `Core-A`: `P0-01 + P0-02`
   目标：先把“从聚合到样本”的路径和 session replay 建起来
2. `Core-B`: `P0-03 + P0-04`
   目标：再把 document/indexing replay 和 deployment window compare 串成完整根因链
3. `Internal-Enhancement`: `P1-01 + P1-02`
   目标：在诊断主链稳定后，再做成本联动和运维入口桥接

---

## 7. 进入技术评估前的产品检查项

- [ ] `P0-01 ~ P0-04` 的范围内 / 范围外已经明确
- [ ] `drill-down / replay` 的目标对象和最小字段集已经清晰
- [ ] `Phase 2A Evaluation Plan` 仍被确认为唯一质量口径
- [ ] `P1` 与 `deferred` 没有重新包装后混入 `P0`
- [ ] `Ask / Search / Detail` 不会因为内部诊断入口而被重新定义为运维主界面
- [ ] `P0-G1` 已被识别为本轮可靠性 guardrail，且不通过新增 session enum 解决
