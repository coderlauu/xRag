# v8 Interaction Delta

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** draft
**对应 PRD：** [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
**对应 Backlog：** [Phase 3B Product Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
**上游交互基线：** [v7 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md)

---

## 1. 文档目的

本说明只定义 `v8 / Phase 3B` 相对 `v7` 的交互增量，不重写整份页面全景。

它解决的问题是：

- `/ops` 如何从只读诊断 workflow 增加 human-in-the-loop recovery planning
- recovery candidate、operator action、execution audit、rollback plan 应如何分工
- 哪些动作允许人工确认执行，哪些必须保持只读或手工 checklist
- 如何避免把内部 recovery UI 变成全自动 remediation 控制台

---

## 2. 不变前提

以下基线在 `Phase 3B` 不改变：

- `Ask / Search / Detail` 仍然是终端用户主入口
- `/ops` 继续定位为内部治理、诊断和 recovery planning 入口
- `diagnostic samples / answer replay / document replay / deployment compare` 继续作为诊断事实入口
- `answered / needs_scope / refused / failed` 的 answer 结果语义不变
- `citation / refusal / freshness / release-readiness` 仍是正式边界
- 不展示 prompt、chain-of-thought、自动修复 agent 或自动 rollback execution

---

## 3. 新增核心对象

### `recovery_candidate`

用于表达一个从诊断事实生成的候选恢复动作。

用户可感知字段至少包括：

- `candidate_id`
- `source_type`
- `source_ref`
- `target_type`
- `target_refs`
- `action_type`
- `risk_level`
- `preconditions`
- `preview_result`
- `recommendation_state`

### `operator_recovery_action`

用于表达一次人工确认后的受控执行。

用户可感知字段至少包括：

- `action_id`
- `candidate_id`
- `actor`
- `reason`
- `target_type`
- `target_refs`
- `idempotency_key`
- `status`
- `started_at`
- `completed_at`
- `diagnosis_code`

### `recovery_audit_entry`

用于回看 recovery action 的执行事实。

用户可感知字段至少包括：

- `action_id`
- `before_facts`
- `after_facts`
- `status_timeline`
- `error_summary`
- `manual_follow_up`

### `rollback_plan`

用于表达围绕 deployment compare 的人工 rollback 判断材料。

用户可感知字段至少包括：

- `deployment_record_id`
- `affected_samples`
- `quality_delta_summary`
- `smoke_summary`
- `confidence`
- `missing_evidence`
- `manual_checklist`

---

## 4. 页面增量

### 4.1 `/ops` 诊断样本详情增加 recovery candidate 区块

#### 新增 / 强化模块

1. Recovery candidates 列表
2. candidate 来源事实摘要
3. risk / preconditions / preview 说明
4. candidate state：recommended、manual-check、observe-only、blocked

#### 交互要求

- candidate 必须展示“为什么出现”，不能只展示动作名称
- blocked candidate 必须说明缺少的事实或前置条件
- observe-only 不能呈现为执行按钮
- candidate 的动作入口必须先进入 preview / confirmation，而不是直接执行

#### 明确禁止

- 一键自动修复
- 用 LLM 文案替代正式事实来源
- 把 candidate 当成确定根因

### 4.2 `/ops` 增加 operator-approved rerun 流程

#### 新增 / 强化模块

1. Preview / dry-run 面板
2. target scope 摘要
3. risk 和 blast radius 提示
4. reason 输入或选择
5. explicit confirmation
6. action status 面板

#### 交互要求

- 没有 preview/dry-run 结果时不显示执行确认
- 确认动作必须显示 target scope 和不可自动回滚的风险
- 执行后进入 action status，不停留在 modal 中等待
- active action 必须有轮询停止条件和 stuck fallback

#### 明确禁止

- 默认勾选确认
- 执行中无限 spinner
- 执行失败后仍显示成功提示
- 自动替换用户可见 answer

### 4.3 `/ops` 增加 recovery audit 详情

#### 新增 / 强化模块

1. action metadata
2. before facts
3. after facts
4. status timeline
5. error / diagnosis code
6. manual follow-up

#### 交互要求

- audit 首屏先回答“这次 action 做了什么，结果是什么”
- before/after facts 应可追溯到 diagnostic sample、document replay、answer replay 或 deployment compare
- failed action 必须展示下一步人工检查建议

#### 明确禁止

- 只显示操作日志文本，不显示事实变化
- 前端自行推断成功与否
- 隐藏 partial failure

### 4.4 `/ops` deployment compare 增加 rollback plan

#### 新增 / 强化模块

1. rollback plan summary
2. affected samples / clusters
3. quality delta / smoke / deployment facts 对照
4. confidence 和 missing evidence
5. manual checklist

#### 交互要求

- plan 必须清楚标注“manual checklist”，不提供自动 rollback 按钮
- plan 要区分 suspected new regression 与 existing debt
- 缺少证据时必须显示 missing evidence，而不是给出强结论

#### 明确禁止

- 自动 rollback execution
- 把 checklist 呈现为系统已执行的动作
- 把旧债务确定标记为新回归

### 4.5 `Ask / Search / Detail`

#### 新增 / 强化模块

1. 默认不新增 recovery UI
2. 内部人员可从已有诊断入口跳回 `/ops`

#### 交互要求

- 终端用户主流程不复制 recovery action 状态
- 如后续需要暴露轻量提示，必须通过 contract freeze 明确范围

---

## 5. 关键状态规则

1. recovery candidate 是从事实生成的建议，不是已执行 action。
2. operator action 是人工确认后的执行事实，必须有 active / terminal 状态。
3. audit entry 是 execution facts，不是前端日志。
4. rollback plan 是人工 checklist，不是自动 rollback。
5. active action 必须最终停止：成功、失败、取消或服务端 reconciliation 后失败。
6. 前端可以停止无意义轮询并提示 stuck，但不能伪造服务端 terminal state。
7. 所有 mutation 前必须有 preview/dry-run 和显式确认。

---

## 6. 本轮明确不纳入交互 delta 的内容

- 自动 remediation 控制台
- 自动 rollback 按钮
- 多人审批流
- 团队权限管理
- 自由文本日志搜索
- 多模型成本与路由大盘
- 终端用户可见 recovery 状态页
