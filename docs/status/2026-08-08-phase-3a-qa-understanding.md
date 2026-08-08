# 里程碑状态：Phase 3A 记忆 + 改写 + 意图

- `phase`: Phase 3 / AI 知识问答核心链路 / 3A
- `date`: 2026-08-08
- `status`: **已确认关闭**（2026-08-08）
- `plan`: [Phase 3 执行计划](../exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)
- `lanes`: [3A 分角色任务书](../exec-plans/active/2026-08-07-qa-pipeline-3a-implementation-lanes.md)
- `matrix`: [测试矩阵](../../tech/qa-pipeline/test-matrix.md)（146 条全部回写）

## 1. 交付范围

17 张原子工单全部 complete。可演示的垂直切片：

> 用户发问 → 消息原子持久化并建对话 → 按 8/12 水位线加载历史与增量摘要 → 改写为最多三个独立
> 子问题 → 固定一个意图树 revision 并有界并行评分 → 稳定保存歧义引导或声明式检索动作 →
> 前端呈现真实终态与 3A 验收信息；运营可在意图树页管理整棵路由表。

后端 `com.app.qa`：领域词汇与配置校验、`qa_*` DDL（V7/V8）、对话/消息/本轮聚合与幂等、
版本化意图树 revision、记忆摘要、查询改写、有界并行评分、歧义与动作映射、理解编排、
两组 HTTP API。前端：问答 A（Conversation + Inspector）与意图树 C（Route Canvas）两页。

## 2. 已验证事实

| 范围 | 证据 |
|---|---|
| 后端 | `./mvnw -B verify` 508 项，0 失败 0 错误，5 跳过（真实 API 用例无 Key 自动跳过） |
| 前端 | `pnpm build` / `lint` / `test` 全通过，51 项 |
| 整体门禁 | `scripts/ci-validate.sh` 全链路跑通；CI 的 `validate` job 直接调用同一脚本 |
| 自动化用例 | 130 条 `A` 全部有测试方法落点并通过（矩阵 §15） |
| 手工走查 | 16 条 `M` 由项目所有者在 Chrome + 1440/720/375px、真实 DashScope Key 下走查，全部 passed（矩阵 §16） |
| 隐私边界 | 标记串扫描 `qa_turn_stage` 全部文本列、六个 `/api/qa` 端点与全量日志，含反向断言 |
| 无 Key 行为 | 应用照常启动、一轮提问仍收敛为 `ACTIONS_READY`，见 [deploy/README](../../deploy/README.md) |

**证据边界**：手工走查无截图归档，形式是所有者本人确认；且它走的是真实模型链路，不可重放。
回归保证以 130 条自动化用例为准，不以那一次走查为准。

**同日晚些时候的减项**：问答页在 3B Step B 被改判为 C — Focused Chat（[PRD §11.1](../prd/2026-08-07-qa-pipeline-prd.md)），
`3A-UI-07`（1440/720/375 三栏收起与抽屉）随之作废，需按 C 重走。本表其余 15 条 `M` 与
130 条 `A` 均不受影响，逐条核对见[测试矩阵 §12.1](../../tech/qa-pipeline/test-matrix.md)。
3A 的关闭结论不因此撤销——作废的是一条走查证据，不是已交付的能力。

## 3. 递延项

| 项 | 状态 | 归属 |
|---|---|---|
| 意图树阻塞节点只显示数量，不逐条列出路径 | 已知裁决 | `blockingNodes` 只回 nodeId，逐条展示需在当前快照反查，且被阻塞节点可能已不在当前 revision；3B 若有需要再评估 |
| 收费 Provider 真实准入 | blocked | Phase 2 工单 23，并行会话推进中；也是 3B 的前置 |
| 多实例部署 | 不在范围 | 意图树进程内缓存与启动回收都假定单实例，见 ADR 0002 / 0007 |
| 一次误提交并入并行会话文件 | 待裁决 | `6112651` 含 `.scratch/model-scheduling/issues/23-*.md` 的工作区改动；内容无损，是否改写历史待定 |

## 4. 3B 准入条件

1. **本文件与复盘经项目所有者确认，3A 关闭**（Phase 3 计划第 274 行）。
2. **DashScope 原生 Rerank adapter 落地**——计划 §5.5 Step C 把它定为 3B 前置工单，
   明确不能以 `enabled: true` 单个配置值顶替。当前由并行会话推进。
3. **检索策略尚无 ADR**。3B Step C 冻结通道构成、粗排、去重、截断与精排选型时，
   若构成关键偏离需随方案补 ADR，不能假设已有定论。
4. ~~3B 是否需要独立 ui-spec 与原型~~ **已裁决（2026-08-08）：不单出**。3B 不新增页面，
   界面变化以增量形式并入 [conversation ui-spec](../../tech/qa-pipeline/conversation/ui-spec.md)
   与既有原型（§5.1 已为来源/工具预留右栏位置）。裁决理由记录在此以免日后被当成"又一次静默跳过"：
   这是项目所有者对"有没有新界面"的显式否决，不是省略闸门本身——增量仍要经确认后才写前端代码。

## 5. 冻结的契约

3A 的 API、稳定终态与降级码已在 [api.md](../../tech/qa-pipeline/api.md) 冻结。3C 迁移只能扩展状态机，
不能改写 `ACTIONS_READY` / `WAITING_CLARIFICATION` 的历史含义；`taskId` 到 3C 才创建，
不复用 `turnId` 或 `logicalCallId`。
