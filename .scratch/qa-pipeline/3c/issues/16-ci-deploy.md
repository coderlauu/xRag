# 16 — CI、部署与 Phase 3 门禁

**Blocked by:** 15  
**Status:** complete（2026-08-12；`scripts/ci-validate.sh` exit 0）

登记全部 generation 配置、关停语义和计费守门；将确定性流式/竞态测试纳入统一验证。

**Acceptance:** `scripts/ci-validate.sh` 全绿；部署契约逐项反证；产出 Phase 3 关闭所需证据但不提前写 Step G。

**Evidence:** 8 个 `QA_GENERATION_*` 变量同时登记于应用、staging/production 样例、Compose 与运维文档；
部署检查反证 Compose 漏传和真实计费测试失去环境变量守门；API 容器保留 30s 关停窗口，Spring 显式调用
`GenerationTasks.close()`。62/62 矩阵与浏览器证据见 `tech/qa-pipeline/test-matrix.md` §35。Phase 3
Step G 状态/复盘未在本工单提前填写。
