# xRag Phase 3B PRD

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** active
**上游基线：** [v7 Handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 Status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A Release Readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-release-readiness.md), [Ask Active Session Stuck Polling Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

---

## 1. 一句话定位

在 `Phase 3A` 已把 `/ops` 做成只读诊断与样本回放 workflow 后，`Phase 3B` 不再只回答“问题是什么”，而是把“下一步该如何受控处理”产品化为 human-in-the-loop ops recovery：系统生成候选恢复动作，人确认执行，系统记录审计与结果。

---

## 2. 背景

`Phase 3A` 已经解决了诊断链路的核心缺口：

- `/ops` 可以从 diagnostic samples 进入 answer replay、document replay 和 deployment compare
- API read models 已能聚合样本、answer session、document pipeline 和 release window 的诊断事实
- Ask active-session stuck polling 已纳入可靠性 guardrail，服务端终态收口和前端停止条件已有回归保护
- release-readiness 已通过 GitHub Actions run `24565067913`

但当前仍存在下一个明显产品缺口：

- 值班者已经能定位一批受影响样本，但仍需要离开产品界面去判断“是否应该 rerun、reindex、观察还是回滚”
- document/indexing 异常与 answer session 异常可以被回放，但系统还没有把它们收敛成可审查的 recovery candidate
- rerun 或 recovery 一旦从脚本或人工 runbook 执行，很难在产品里留下 action id、before/after facts、失败原因和执行轨迹
- deployment compare 能帮助判断发布窗口风险，但还没有产出人工可审查的 rollback plan 或 manual checklist
- production 历史 `not_indexed` 治理仍是运维事项，不能在没有 dry-run 事实的情况下直接产品化

因此 `Phase 3B` 的目标不是进入全自动 remediation，而是把诊断后的受控处理路径做成正式产品语义，为后续技术评估和 contract freeze 提供清晰边界。

---

## 3. 本期目标

- 从 `v7` 的诊断事实生成 recovery candidate，而不是让操作者手工推断下一步
- 支持人工确认后的受控 rerun，优先评估 document indexing rerun 和 answer diagnostic rerun
- 为每次 recovery action 建立 execution audit：谁执行、为什么执行、作用对象、执行前后事实、终态和错误
- 围绕 deployment compare 生成 guarded rollback plan 和 manual checklist，但不自动执行 rollback
- 将任何新的长任务或 queue-driven action 纳入服务端终态收口、queue 对账和前端停止条件的可靠性约束
- 在进入实现前判断哪些能力需要 `contract-guard`，哪些能力需要 `answer-quality-gate`

---

## 4. 非目标

- 全自动 remediation
- 全自动 rerun
- 全自动 production rollback
- 多模型治理、provider / model routing 平台
- 团队协作权限、审批流和多角色组织模型
- 开放互联网联网回答
- 浏览器插件或移动端
- 重写 Ask / Search / Detail 主链路响应 contract
- 把 production 历史 `not_indexed` 回补直接并入产品 P0

`Phase 3B` 先解决“如何从诊断事实走到可审查的人工处置”，不承诺让系统自主决定和执行处置。

---

## 5. 目标用户

### 主用户

- 值班运维 / 值班开发
- 工程负责人
- 产品负责人

### 次级用户

- 需要验证 recovery 结果的 QA / 内测人员
- 需要回看恢复动作证据的发布负责人

### 非目标用户

- 只关心直接提问结果的一般终端用户
- 不具备内部操作权限的外部用户

`Phase 3B` 仍是内部 ops 版本，不是终端问答主体验改版。

---

## 6. 核心场景

### 场景 1：从诊断样本生成恢复候选

值班者在 `/ops` 看到异常样本后，需要系统告诉他这更像是哪类可处理问题：

- document/indexing backlog
- document/indexing failure
- answer session stuck / failed
- release-window regression
- 需要继续观察的历史运行债务

系统应给出候选动作、影响对象、风险、前置条件和 preview/dry-run 结果，而不是只给一个“修复”按钮。

### 场景 2：人工确认后 rerun

当 document replay 表明某个文档或一批文档适合 rerun indexing，操作者需要在执行前看到：

- 目标对象数量和范围
- 当前状态和最近失败原因
- rerun 是否幂等
- 预计影响的 answer/session/corpus readiness
- 失败时如何停止与回滚

只有人工确认后，系统才允许进入执行。

### 场景 3：查看 recovery action 审计

当一次 recovery 被执行后，团队需要回看：

- action id
- actor
- reason
- target type / target ids
- before facts
- after facts
- status timeline
- error / diagnosis code

审计结果必须能解释一次 action 是否真的改善了问题，不能只记录“按钮被点过”。

### 场景 4：发布风险下生成 rollback plan

当 deployment compare 显示发布后出现疑似新增回归，系统需要产出一个人工可审查的 rollback plan：

- 受影响样本和 cluster
- before / after 指标变化
- smoke 与 deployment facts
- 推荐人工检查项
- 明确是否仍需继续观察

系统不能直接执行 rollback，也不能把人工 checklist 包装成自动化结论。

---

## 7. 优先级原则

1. 先把 recovery action 的事实语义定义清楚，再讨论 API/schema
2. 任何 mutation 都必须有 preview/dry-run、人工确认、审计和失败终态
3. 复用 `v7` diagnostic read models，不重建诊断影子数据
4. 长任务和 queue action 必须满足服务端终态收口，不允许留下永久 active 状态
5. rollback 在本期只做 planning，不做自动 execution
6. production `not_indexed` 必须先 dry-run，再决定是否进入产品范围
7. 多模型治理和团队协作权限不混入 `P0`

---

## 8. 需求细化与优先级拆分

### 8.1 P0 核心交付

| ID | 能力 | 需求细化 | 为什么是 P0 |
| --- | --- | --- | --- |
| `P0-01` | Recovery Candidates | 从 diagnostic samples、answer replay、document replay、deployment compare 生成可解释恢复候选，包含来源事实、目标对象、风险、前置条件和 preview/dry-run 结果。 | `v7` 已能定位问题，但还没有把诊断事实转化为可审查下一步。 |
| `P0-02` | Operator-approved Rerun | 支持人工确认后的受控 rerun，优先覆盖 document indexing rerun 和 answer diagnostic rerun；默认 preview-first。 | 这是从只读诊断进入受控处置的最小闭环。 |
| `P0-03` | Recovery Execution Audit | 每次 operator action 记录 action id、actor、target、reason、before/after facts、status、error 和时间线。 | 没有审计，任何恢复动作都无法进入生产级运维工作流。 |
| `P0-04` | Guarded Rollback Planning | 基于 deployment compare 生成 rollback plan 和 manual checklist，不自动执行 rollback。 | 发布风险需要进入下一步判断，但自动 rollback 风险过高，必须先做人工计划。 |
| `P0-G1` | Recovery Action Liveness Guardrail | 新的 recovery action 必须有服务端终态收口、queue 对账、前端停止条件和测试证明。 | `v7` 的 stuck polling 复盘已证明异步状态缺少 liveness 会直接破坏用户信任。 |

### 8.2 P1 补强

| ID | 能力 | 需求细化 | 为什么下放到 P1 |
| --- | --- | --- | --- |
| `P1-01` | Recovery Runbook Links | 在 candidate、execution 和 rollback plan 中链接 pgweb、CI artifacts、inspection guide 等现有入口。 | 有帮助，但必须先让 candidate/action/audit 本身成立。 |
| `P1-02` | Batch Recovery Preview | 对一批 document 或 sample 做更丰富的批量影响预估。 | 会扩大 blast radius 和测试矩阵，先从小范围受控 action 开始。 |
| `P1-03` | Recovery Outcome Analytics | 汇总不同 recovery action 的成功率、耗时和复发率。 | 需要足够 execution audit 数据积累，不能作为第一版前置。 |

### 8.3 明确延后

- 自动 remediation agent
- 自动 rollback execution
- 团队审批流和权限模型
- 多模型治理与成本策略平台
- 自由文本日志检索台
- 终端用户可见的恢复状态页

---

## 9. 阶段切片建议

### Slice A：候选生成与 preview

先交付：

- `P0-01` Recovery Candidates
- `P0-G1` 的状态语义与停止条件约束

目标：

- 让操作者看到可审查的下一步，而不是直接执行。

### Slice B：人工确认 rerun 与审计

继续补齐：

- `P0-02` Operator-approved Rerun
- `P0-03` Recovery Execution Audit

目标：

- 完成最小 human-in-the-loop recovery 闭环。

### Slice C：rollback plan

最后补：

- `P0-04` Guarded Rollback Planning

目标：

- 把 deployment compare 的诊断结论转为人工可执行检查清单，但不自动 rollback。

---

## 10. 验收标准

### 10.1 Phase 3B Gate

1. 操作者能从 diagnostic sample 看到至少一种 recovery candidate，并理解来源事实、目标对象、风险和前置条件。
2. 对任何真实 mutation，系统必须先提供 preview/dry-run 和人工确认，不允许直接执行。
3. 每次 recovery action 都有 action id、actor、reason、target、before facts、after facts、status、error 和时间线。
4. 任意 recovery action 的 active 状态最终会进入 terminal status，或被服务端 reconciliation 标记为 failed。
5. rollback 只产出 plan / compare / checklist，不自动执行生产 rollback。

### 10.2 Phase 3B 完整度补强

1. Recovery candidate 不得与 `v7` diagnostic facts 冲突。
2. Rerun 不得改写 answer/citation/freshness/refusal 事实口径，除非通过 `answer-quality-gate`。
3. 新增 API/schema/shared-types/OpenAPI/API client 变化必须通过 `contract-guard`。
4. production `not_indexed` 回补必须先 dry-run，并明确是否只是 runbook。
5. `/ops` 仍是内部工作流，不反向污染 Ask / Search / Detail 主心智。

---

## 11. 对技术方案评估的要求

进入 `technical-evaluation` 前后，必须回答：

- recovery candidate 是纯 read model，还是需要持久化 candidate snapshot
- operator action 是否需要新 durable table、状态机、queue job type、API path 和 SDK contract
- action 幂等键如何定义，避免重复执行同一 recovery
- before/after facts 如何采样，避免审计记录与正式事实链分叉
- recovery action active / terminal 状态如何定义，谁负责服务端终态收口
- answer diagnostic rerun 是否触碰 answer quality、citation、freshness、refusal 或 eval 语义
- production `not_indexed` dry-run 的影响面是否足以进入 v8 P0，还是应保持为 runbook

这些问题没有冻结前，不进入实现 lane。
