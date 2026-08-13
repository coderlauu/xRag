# 分角色任务书：Phase 4 RAG 评测体系

## 1. Metadata

- `status`: Step C review
- `parent`: [Phase 4 计划](2026-08-12-rag-evaluation-phase-plan.md)
- `prd`: [PRD](../../prd/2026-08-13-rag-evaluation-prd.md)
- `tech`: [架构](../../../tech/evaluation/architecture.md)、[数据模型](../../../tech/evaluation/data-model.md)、[API](../../../tech/evaluation/api.md)
- `adr`: [ADR 0010](../../adr/0010-versioned-evaluation-runs.md)、[ADR 0011](../../adr/0011-framework-neutral-semantic-evaluation.md)
- `matrix`: [100 条测试矩阵](../../../tech/evaluation/test-matrix.md)
- `issues`: [.scratch/evaluation](../../../.scratch/evaluation/README.md)

## 2. Objective

交付无专用页面的评测闭环：不可变数据集、受控夹具、同源 turn 录制、有界运行、确定性指标、中文 Judge
PoC/可替换 adapter、版本化报告、人工复核、A/B、保留与 CI/付费守门。

## 3. Lane

| Lane | 写入范围 | 不得写入 |
|---|---|---|
| 产品 | PRD、校准/主力/保留样本与标注规范 | Schema、框架实现、课程阈值照搬 |
| 后端 | `com.app.evaluation`、V12+、必要的 QA identity 补充 | 前端、第二条检索 Pipeline、RAGAS 类型外泄 |
| 评测适配 | `evaluation-worker/`（仅 PoC 通过后）与锁文件/Prompt artifact | 生产数据库直连、run 状态所有权 |
| 测试 | matrix、fixtures、fake Provider/Judge、契约/E2E | 绕过深模块测试内部 repository |
| 运维 | CLI、CI、预算/retention 配置、真实守门 | 默认 CI 真实付费、秘密入仓库 |

第一版无前端 Lane；这是已确认产品裁决，不是漏项。

## 4. 工单

| # | 工单 | 产品 | 后端 | 适配 | 测试 | 运维 |
|---:|---|:---:|:---:|:---:|:---:|:---:|
| 01 | 领域闭集、canonical identity 与配置 | ● | ● | | ● | ● |
| 02 | V12 评测事实 Schema | | ● | | ● | |
| 03 | Dataset 深模块与发布 API | ● | ● | | ● | |
| 04 | Fixture manifest 与就绪检查 | ● | ● | | ● | ● |
| 05 | Run 状态机、预算、claim 与恢复 | | ● | | ● | ● |
| 06 | QA 同源执行 seam 与 recording assembler | ● | ● | | ● | |
| 07 | 确定性指标引擎与资格 | | ● | | ● | |
| 08 | 报告、failures、A/B 与 baseline | ● | ● | | ● | |
| 09 | 人工 review 与 adopted 聚合 | ● | ● | | ● | |
| 10 | Semantic seam、fake 与 worker contract | | ● | ● | ● | |
| 11 | 12 条中文 Judge/RAGAS PoC | ● | | ● | ● | ● |
| 12 | PoC 裁决后的生产 semantic adapter | ● | ● | ● | ● | ● |
| 13 | HTTP/CLI 受控操作面 | | ● | | ● | ● |
| 14 | Retention、隐私与失败注入 | | ● | ● | ● | ● |
| 15 | 100 条矩阵、真实 baseline 与人工验收 | ● | ● | ● | ● | ● |
| 16 | CI、部署、付费守门与文档收口 | | ● | ● | ● | ● |

## 5. 依赖前沿

```text
01 ──┬─> 03 ─> 04 ─┐
02 ──┴─> 05 ───────┼─> 06 ─> 07 ─> 08 ─> 09 ─┐
                    └────────────> 10 ─> 11 ─> 12 ─┤
                                      08 ──────────> 13 ─> 14 ─> 15 ─> 16
```

- Step C 确认后第一前沿只解锁 01、02；不并行编辑同一迁移或领域闭集。
- 11 是硬裁决点；12 在项目所有者确认 RAGAS/自有 Judge/暂不接语义评分后才解锁。
- 06 必须走正常 Phase 3 turn，禁止为了赶进度先做 `/rag/eval` 旁路。

## 6. 完成定义

- 技术三件套、ADR、100 条矩阵、任务书与 16 张工单经项目所有者确认。
- 自动矩阵全绿；6 条真实/人工项有逐项证据；默认 CI 零真实费用。
- 12 条中文校准与至少 24 条主力样本形成 baseline；保留集不用于日常调参。
- manifest、逐样本、Markdown/CSV/JSONL、failures、review、A/B 可互相追溯。
- 不产出评测页面、slides、排行榜或真实工具执行器。
