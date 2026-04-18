# Phase 3B P0 Technical Tradeoffs

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** draft
**适用范围：** `P0-01 ~ P0-04`, `P0-G1`
**对应文档：**
- [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- [Phase 3B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
- [v8 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md)
- [v8 Handoff](/Users/coderlauu/xRag/docs/handoff/v8.md)
- [v7 Handoff](/Users/coderlauu/xRag/docs/handoff/v7.md)
- [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- [Ask Active Session Stuck Polling Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

---

## 1. 本文档目的

这份文档用于在进入 `Phase 3B` 技术评估前，先把最关键的方向性取舍收敛到可以继续冻结的范围。

它不回答“具体表结构和 API 怎么改”，而是先回答：

- `Phase 3B` 是否应该从只读诊断推进到 human-in-the-loop recovery
- recovery candidate 是否应先作为 read model，还是直接持久化和执行
- rerun / recovery 是否可以没有 preview、人工确认和审计
- rollback 是否进入自动执行
- 新的异步 recovery action 如何继承 stuck polling 复盘的 liveness 约束

---

## 2. 当前工程事实

基于当前仓库和最新主线基线，`Phase 3B` 的 tradeoff 必须建立在以下事实之上：

1. `v7 / Phase 3A` 已完成正式收口，并已归档为上游诊断基线。
2. `/ops` 已具备 diagnostic samples、answer replay、document replay 和 deployment compare。
3. GitHub Actions run `24565067913` 已成功，`v7` release-readiness baseline 可作为 `v8` 启动前绿态。
4. Ask active-session stuck polling 复盘已经明确：异步 active 状态必须有服务端终态收口，前端轮询必须有停止条件。
5. `Phase 2A / 2B / 2C / 3A` 已冻结 `citation / refusal / freshness / release-readiness` 与 ops diagnostic contract；`Phase 3B` 不能为了 recovery 便利破坏这些边界。
6. production 历史 `not_indexed` 文档可能存在，但当前事实只支持先 dry-run，不支持直接纳入产品 P0。

---

## 3. 已收敛方向

### 3.1 `Phase 3B` 以 human-in-the-loop recovery 为核心，不进入全自动 remediation

**结论**

- 本轮主线是“候选 -> preview -> 人工确认 -> 执行审计”。
- 系统可以推荐、解释和记录，但不能自主决定和执行 remediation。

**原因**

- `v7` 已解决“看清问题”，自然下一步是“把人工处置路径产品化”。
- 自动 remediation 会立即引入权限、审批、blast radius、回滚和责任归属问题，超出本轮最小闭环。

**实施约束**

- 任何执行动作都必须有 preview/dry-run 和 explicit confirmation。
- UI 文案与 API contract 都必须区分 candidate 与 executed action。

### 3.2 recovery candidate 优先作为 read model 评估，只有执行或审计 facts 才默认需要持久化

**结论**

- candidate 可以先作为从 diagnostic facts 派生的 read model 进入技术评估。
- operator action 与 execution audit 更可能需要 durable storage。

**原因**

- candidate 是诊断事实的解释和下一步建议，如果过早持久化，容易引入 snapshot freshness 和去重复杂度。
- action/audit 是生产运维事实，必须可追踪、可复盘、可查询。

**实施约束**

- 技术评估必须回答 candidate 是否需要 stable id，以及 id 是 deterministic 还是 durable。
- before/after facts 若被写入 audit，必须明确采样时机和来源链。

### 3.3 operator-approved rerun 必须 preview-first，不能直接变成脚本按钮

**结论**

- Rerun 必须先展示 target scope、risk、preconditions 和 dry-run/preview。
- 没有 preview 或前置条件不满足时，action 只能 blocked。

**原因**

- recovery action 的风险不在于 UI，而在于对生产数据和异步队列的影响。
- 直接把脚本包装成按钮会失去审计和 blast radius 控制。

**实施约束**

- action execution 必须有 idempotency key。
- queue job failed / stalled / exhausted retries 必须与 action status 对账。
- action active 状态不能永久存在。

### 3.4 recovery audit 是 P0，不是后续增强

**结论**

- 每次 executed recovery action 必须形成审计记录。
- audit 最小字段包括 action id、actor、target、reason、before facts、after facts、status timeline、error。

**原因**

- 没有审计，operator-approved rerun 无法进入生产运维闭环。
- 如果 action 成功或失败无法复盘，后续 outcome analytics 和 rollback 判断都不可信。

**实施约束**

- audit 必须来自服务端事实，不允许前端自行拼日志。
- failed / partial failure 必须显式展示，不能被普通错误 toast 吞掉。

### 3.5 rollback 只做 guarded planning，不做 automatic execution

**结论**

- 本轮只产出 rollback plan、affected samples、quality delta、smoke facts 和 manual checklist。
- 不提供自动 rollback API 或按钮。

**原因**

- rollback 是发布控制与生产变更，不只是 ops 页面动作。
- 当前版本没有权限、审批、环境编排和回滚验证 contract。

**实施约束**

- rollback plan 必须清楚标注 manual。
- plan 应显示 missing evidence，避免把不确定回归包装成确定结论。

### 3.6 production `not_indexed` 先作为 runbook/dry-run，不直接成为 P0 product scope

**结论**

- 若要处理历史 `not_indexed`，先执行 `pnpm recovery:backfill-indexing -- --dry-run` 并记录影响面。
- dry-run 结论可以反馈给 Phase 3B scope，但不能在没有证据时直接推动产品实现。

**原因**

- 历史数据治理可能是一次性运维事项，不一定需要产品化。
- 未知影响面直接纳入 P0 会扩大 migration、worker 和 recovery blast radius。

**实施约束**

- v8 技术评估应显式区分 runbook、dry-run 和 product action。
- 如果 dry-run 证明存在稳定重复需求，再评估是否进入 operator-approved rerun。

---

## 4. 明确排除项

本次 tradeoff closure 同时明确排除以下方向进入 `P0`：

- 自动 remediation agent
- 自动 production rollback
- 自动全量 reindex
- 团队权限、审批流和多角色组织模型
- 多模型 routing、成本治理和 provider 策略平台
- 自由文本日志检索台
- 终端用户可见 recovery 状态页

---

## 5. 进入后续技术评估前必须回答的问题

1. `recovery_candidate` 是否需要持久化，还是可以由 source facts deterministic 生成？
2. `operator_recovery_action` 的状态机有哪些 active 与 terminal 状态？
3. action idempotency key 如何定义，防止重复 rerun？
4. `Recovery Execution Audit` 是否需要新 table，还是能复用既有 deployment/evaluation facts？
5. before/after facts 的采样时机、保留策略和查询入口是什么？
6. document indexing rerun 的最小安全目标范围是什么？
7. answer diagnostic rerun 是否会触碰 answer quality、citation、freshness、refusal 或 eval contract？
8. queue failed / stalled / exhausted retries 如何与 action status 对账？
9. rollback plan 的 confidence 和 missing evidence 如何计算，避免误导为自动回滚建议？
10. production `not_indexed` dry-run 的结果是否足以证明这是产品 P0，而不是版本外 runbook？

这些问题在 contract freeze 前必须回答，但它们已经不再属于方向选择，而属于实现边界冻结。

---

## 6. 对后续实现的约束

1. 不得把 recovery candidate 呈现为已执行 action。
2. 不得绕过 preview/dry-run 与人工确认执行 mutation。
3. 不得没有审计地执行 rerun。
4. 不得让 recovery action active 状态永久存在。
5. 不得通过新增未冻结 enum 掩盖 liveness 问题。
6. 不得让 answer diagnostic rerun 改写 `citation / refusal / freshness` 口径，除非先通过 `answer-quality-gate`。
7. 不得让 rollback plan 变成隐式 automatic rollback。
