# xRag Phase 3B Product Backlog

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** active
**对应 PRD：** [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
**对应交互：** [v8 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md)
**对应技术取舍：** [Phase 3B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md)

---

## 1. 文档目的

这份文档把 `Phase 3B PRD` 继续收敛成可以进入技术评估的 backlog，用于：

- 明确 human-in-the-loop ops recovery 哪些能力进入 `P0`
- 避免把自动 remediation、权限协作和多模型治理混成一个版本
- 为后续 interaction、tradeoff、technical evaluation 和 contract freeze 提供验收清单

当前阶段仍然只冻结产品边界，不在这里承诺最终实现方案。

---

## 2. 排序原则

1. 先候选和 preview，再 mutation
2. 先单对象或小范围受控 rerun，再批量 recovery
3. 任何执行动作必须有审计，不允许只做前端按钮
4. rollback 先做人工 plan，不做自动 execution
5. 长任务必须有服务端终态收口、queue 对账和前端停止条件
6. production `not_indexed` 不在没有 dry-run 的情况下进入 P0
7. `answer-quality-gate` 和 `contract-guard` 是进入实现前的硬 gate

---

## 3. P0 Backlog

### `P0-01` Recovery Candidates

**用户价值**

- 值班者可以从诊断事实看到下一步可审查动作，而不是自己在样本、脚本和数据库之间推断。

**范围内**

- 从 diagnostic sample 生成 candidate
- 从 answer replay 生成 candidate
- 从 document replay 生成 candidate
- 从 deployment compare 生成 candidate
- 展示 source facts、target、risk、preconditions、preview/dry-run result
- 明确 candidate 是 recommended、manual-check、observe-only 还是 blocked

**明确不含**

- 自动执行候选动作
- 基于 LLM 自主决定 remediation
- 自由文本日志搜索
- 协作评论和任务分派

**验收清单**

- [ ] 操作者能看到 candidate 的来源事实和目标对象
- [ ] candidate 不与 diagnostic sample / replay / deployment compare facts 冲突
- [ ] blocked candidate 能解释缺少什么前置条件
- [ ] observe-only candidate 不会被包装成可执行动作

**前置依赖**

- v7 diagnostic samples
- answer replay
- document replay
- deployment compare

### `P0-02` Operator-approved Rerun

**用户价值**

- 操作者可以在看到 preview 后，人工确认一次受控 rerun，而不是离开产品界面执行不可追踪脚本。

**范围内**

- document indexing rerun 候选
- answer diagnostic rerun 候选
- preview-first confirmation
- action id 和幂等键
- active / terminal status
- execution failure 展示

**明确不含**

- 自动 retry
- 自动批量 rerun
- 自动 reindex 全库
- 自动重答并替换用户可见答案
- 修改 answer/citation/freshness/refusal 口径

**验收清单**

- [ ] 没有 preview/dry-run 时不能执行 rerun
- [ ] 人工确认前不能创建执行态 action
- [ ] 重复确认同一幂等键不会产生重复执行
- [ ] 执行失败时 action 最终进入 terminal failed
- [ ] answer diagnostic rerun 若触碰 quality facts，必须通过 `answer-quality-gate`

**前置依赖**

- recovery candidate
- action status semantics
- queue reconciliation design

### `P0-03` Recovery Execution Audit

**用户价值**

- 团队可以复盘每次 recovery 做了什么、为什么做、对哪些对象做、结果如何。

**范围内**

- action id
- actor
- reason
- target type / target ids
- source candidate id 或 source facts
- before facts snapshot
- after facts snapshot
- status timeline
- error / diagnosis code
- created_at / started_at / completed_at

**明确不含**

- 多角色审批流
- 细粒度权限模型
- 组织级 audit export
- 外部 SIEM 集成

**验收清单**

- [ ] 每个 executed action 都能从 `/ops` 查到审计详情
- [ ] before/after facts 与正式事实链一致
- [ ] audit 记录 terminal status，不留下永久 active
- [ ] 失败 action 能解释错误分类和下一步 manual check

**前置依赖**

- operator action contract
- durable action storage 或等价正式事实源
- failure taxonomy

### `P0-04` Guarded Rollback Planning

**用户价值**

- 发布风险出现时，团队能得到人工可审查的 rollback plan，而不是在聚合指标、样本列表和 CI artifacts 之间重新拼判断。

**范围内**

- 从 deployment compare 进入 rollback plan
- 展示 before / after 指标差异
- 展示 affected samples / clusters
- 展示 smoke 和 deployment facts
- 生成 manual checklist
- 明确 plan confidence 和 missing evidence

**明确不含**

- 自动 rollback
- 自动部署编排
- 灰度发布控制台
- infra 监控替代

**验收清单**

- [ ] plan 能说明“为什么考虑 rollback”
- [ ] plan 能列出人工检查项和缺失证据
- [ ] 页面不提供自动 rollback execution
- [ ] plan 不把旧债务误标成确定新回归

**前置依赖**

- deployment compare
- diagnostic sample source context
- release-readiness facts

### `P0-G1` Recovery Action Liveness Guardrail

**用户价值**

- 操作者不会看到永远 pending 的 recovery action；系统必须最终给出 terminal status 或失败事实。

**范围内**

- recovery action active / terminal 状态定义
- queue failed / stalled / exhausted retries 对账
- worker 前置失败保护
- 前端 polling 最大等待和 stuck fallback
- replay / audit 读取 stuck 或 failed action 时保持事实一致

**明确不含**

- 新增没有 contract freeze 的状态枚举
- 前端伪造 terminal state
- 把 unknown 状态当成功
- 用无限重试替代失败终态

**验收清单**

- [ ] active action 超过服务端阈值后会被收口到 terminal failed 或明确 terminal 状态
- [ ] queue failed / stalled / retries exhausted 后不会留下永久 active action
- [ ] 前端不会无限轮询同一个 active action
- [ ] 测试覆盖成功、失败、queue 对账和前端停止条件

**前置依赖**

- [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)
- action status contract
- queue lifecycle design

---

## 4. P1 Backlog

### `P1-01` Recovery Runbook Links

- 目标：在 candidate、action audit 和 rollback plan 中链接 pgweb、CI artifacts、inspection guide 等现有入口
- 进入条件：`P0-01 ~ P0-04` 的对象语义已冻结
- 验收重点：链接只是辅助入口，不替代正式 recovery facts

### `P1-02` Batch Recovery Preview

- 目标：对一批 document、sample 或 session 做批量影响预估
- 进入条件：单对象或小范围 recovery 已具备审计和失败终态
- 验收重点：批量 preview 必须显示 blast radius，不能默认可执行

### `P1-03` Recovery Outcome Analytics

- 目标：汇总 recovery action 的成功率、耗时、复发率和失败分类
- 进入条件：execution audit 有足够数据积累
- 验收重点：analytics 只能读取 audit facts，不新造第二套结果口径

---

## 5. Deferred

- 自动 remediation agent
- 自动 rollback execution
- 多角色审批流与团队权限
- 多模型 routing / cost governance
- 自由文本日志检索台
- 终端用户可见 recovery 状态
- 外部告警订阅与通知编排

这些能力统一视为后续候选，不得在 `Phase 3B` 技术评估里默认带入。

---

## 6. 阶段顺序

1. `Core-A`: `P0-01 + P0-G1`
   目标：先把 recovery candidate 和 liveness guardrail 冻结
2. `Core-B`: `P0-02 + P0-03`
   目标：建立 operator-approved rerun 与 execution audit 最小闭环
3. `Core-C`: `P0-04`
   目标：把 deployment compare 转化为人工 rollback plan
4. `Internal-Enhancement`: `P1-01 ~ P1-03`
   目标：在 recovery 主链稳定后，再补 runbook、批量 preview 和 outcome analytics

---

## 7. 进入技术评估前的产品检查项

- [ ] `P0-01 ~ P0-04` 的范围内 / 范围外已经明确
- [ ] candidate、action、audit、plan 的对象语义已经区分
- [ ] mutation 必须 preview-first 和 human-confirmed
- [ ] rollback execution 已明确延后
- [ ] production `not_indexed` 不在没有 dry-run 的情况下进入 P0
- [ ] `contract-guard` 与 `answer-quality-gate` 的触发条件已经写入
- [ ] Ask active-session stuck polling 复盘的 liveness 约束已转入 recovery action guardrail
