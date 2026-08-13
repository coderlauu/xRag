# Phase 4 RAG 评测工单

- `status`: blocked-on-step-c-review
- `plan`: [实施任务书](../../docs/exec-plans/active/2026-08-13-rag-evaluation-implementation-lanes.md)
- `matrix`: [测试矩阵](../../tech/evaluation/test-matrix.md)

16 张工单按依赖图执行。Step C 未确认前全部 blocked；确认后仅 01/02 ready。每张工单必须在完成时回写
测试 ID、实际命令、偏离与提交，不以“代码已写”替代验收。

| # | 标题 | 初始状态 | 依赖 |
|---:|---|---|---|
| 01 | 领域闭集与 canonical identity | blocked | Step C |
| 02 | V12 评测事实 Schema | blocked | Step C |
| 03 | Dataset 深模块 | blocked | 01,02 |
| 04 | Fixture 就绪检查 | blocked | 03 |
| 05 | Run 状态机与预算恢复 | blocked | 01,02 |
| 06 | 同源执行与录制 | blocked | 04,05 |
| 07 | 确定性指标 | blocked | 06 |
| 08 | 报告与 A/B | blocked | 07 |
| 09 | 人工复核 | blocked | 08 |
| 10 | Semantic seam | blocked | 01,06 |
| 11 | 中文 Judge PoC | blocked | 10 |
| 12 | 生产 semantic adapter | blocked | 11 + owner decision |
| 13 | HTTP/CLI | blocked | 08,12 |
| 14 | Retention/隐私 | blocked | 13 |
| 15 | 矩阵与 baseline | blocked | 09,12,14 |
| 16 | CI/部署/收口 | blocked | 15 |
