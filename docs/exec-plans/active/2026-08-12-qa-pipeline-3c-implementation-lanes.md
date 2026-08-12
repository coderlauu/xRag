# 分角色任务书：Phase 3C 生成 + 流式 + 任务管控

## 1. Metadata

- `status`: implemented / validated（2026-08-12；16/16 工单 complete，等待 Phase 3 Step G 关闭）
- `parent`: [Phase 3 执行计划](2026-08-07-qa-pipeline-phase-plan.md)
- `prd`: [PRD §13](../../prd/2026-08-07-qa-pipeline-prd.md)
- `tech`: [架构 §23~§31](../../../tech/qa-pipeline/architecture.md)、[数据模型 §18~§21](../../../tech/qa-pipeline/data-model.md)、[API §14~§19](../../../tech/qa-pipeline/api.md)
- `adr`: [ADR 0009](../../adr/0009-single-settlement-generation-task.md)
- `ui`: [conversation ui-spec §15](../../../tech/qa-pipeline/conversation/ui-spec.md)
- `matrix`: [测试矩阵 §27~§35](../../../tech/qa-pipeline/test-matrix.md)（62 条）
- `issues`: [.scratch/qa-pipeline/3c](../../../.scratch/qa-pipeline/3c/README.md)

## 2. Objective

把 3A 的理解与 3B 的依据组装成安全 Prompt，经 `com.app.ai` 流式生成，并以单一结算边界实现排队、
停止、断连恢复、持久化和唯一终态。完成后 Phase 3 首次形成真实可回答链路。

## 3. Scope

In scope：V11、Prompt codec/budget、生成深模块、FIFO 许可、SSE adapter、停止/恢复 API、问答页 3C 增量、
竞态/隐私/浏览器/CI 验收。

Out of scope：WebSocket、SSE 重放、多实例分布式队列、再生成/继续生成、流式 Markdown、第二家 Chat
供应商、生成中二次工具循环、RAG 质量评测。

## 4. 工单与 Lane

| # | 工单 | 产品 | 后端 | 前端 | 测试 | 运维 |
|---:|---|:---:|:---:|:---:|:---:|:---:|
| 01 | 3C 配置与领域闭集 | | ● | | ● | ● |
| 02 | V11 任务与消息迁移 | | ● | | ● | |
| 03 | 不可信输入 codec 与 Prompt 预算 | ● | ● | | ● | |
| 04 | FIFO 队列与公平许可 | | ● | | ● | ● |
| 05 | 单一结算器与持久化 | | ● | | ● | |
| 06 | 生成编排深模块 | ● | ● | | ● | |
| 07 | 问答 SSE emitter adapter | | ● | | ● | ● |
| 08 | 流式提交与停止 API | ● | ● | | ● | |
| 09 | 断连、恢复与生命周期 | | ● | | ● | ● |
| 10 | 前端流式状态与停止 | ● | | ● | ● | |
| 11 | 前端重连、未保存与无障碍 | ● | | ● | ● | |
| 12 | Prompt/协议隐私审计 | | ● | ● | ● | |
| 13 | 竞态、背压与压力测试 | | ● | | ● | |
| 14 | 3C E2E 与冻结契约回归 | | ● | ● | ● | |
| 15 | 矩阵回写与浏览器验收 | ● | | ● | ● | |
| 16 | CI、部署与 Phase 3 门禁 | | ● | ● | ● | ● |

## 5. Lane 护栏

- 产品：只使用 PRD §13 状态闭集；答案纯文本；等待超时与系统故障不可合并。
- 后端：调用方只跨 `GenerationTasks` seam；模型与 SSE send 不在事务内；所有终结入口只走结算器。
- 前端：不复制原型；不用 EventSource；流式文本不用逐字 aria-live；断连不伪造打字动画。
- 测试：在深模块接口断言结果，不测试内部 Semaphore 实现；负向隐私断言必须配正向功能断言。
- 运维：并发、队列、deadline、SSE 与 push executor 配置成组登记并做启动校验；真实调用显式守门。

## 6. 依赖前沿

```text
01 ──┬─> 03 ───────────────┐
     ├─> 04 ─> 05 ─> 06 ─> 07 ─> 08 ─> 09 ─┐
02 ──┴────────> 05 ────────┘                ├─> 12 ─> 13 ─> 14 ─> 15 ─> 16
                                  08 ─> 10 ─┴─> 11 ────────────────┘
```

- Step C 确认后第一前沿仅 01、02；其余保持 blocked。
- 06 必须同时消费 Prompt、队列和结算器，不允许先做一个绕过它们的临时生成 Controller。
- 08 是显式接线工单，防止再次出现“模块和契约都建了但真实链路没调用”的 3B 缺口。

## 7. Validation

- `scripts/ci-validate.sh` 全绿；62 条矩阵逐项有落点并回写。
- 浏览器走通 ui-spec §15 七个场景与四档宽度。
- 1000 轮结算竞态无重复消息、终态、许可或订阅泄漏。
- 一次显式守门的真实 `qwen-plus` 流式调用验证首包、delta、完成和脱敏，不进入默认 CI。

## 8. Step C 完成定义

本文件、技术三件套、ADR 0009、62 条矩阵与 16 张工单已于 2026-08-12 经项目所有者确认。实现后
16/16 工单 complete，62/62 矩阵回写，浏览器七场景与四档宽度通过，`scripts/ci-validate.sh` exit 0。
本任务书只记录实施与验收证据；Phase 3 Step G 复盘、里程碑关闭与计划归档仍由父计划处理。
