# 16 — 矩阵执行与双界面浏览器验收

**What to build:** 执行完整 3A 自动化和两页手工矩阵，回写真实证据；修复实现缺陷但不改规格把失败变通过。

**Blocked by:** 01~15
**Status:** blocked

## Slice

- 执行 backend verify、frontend tests/build/lint 和测试报告核对。
- 在 1440/720/375px 走查问答 A 的 8 个、意图树 C 的 8 个场景。
- 记录浏览器、commit、配置、日期、结果和无敏感截图。
- failed/blocked 建工单并保留状态；范围变化回 Phase 3 计划 Decision Log。

## Acceptance

- [ ] 测试矩阵所有 `A` 有测试落点与执行证据，所有 `M` 有实际走查记录。
- [ ] 3A-UI-01~16 与 3A-E2E-08 如实回写 passed/failed/blocked。
- [ ] 项目所有者完成 Step D 浏览器演示确认和 Step E 结果确认后才关闭。
- [ ] 不使用原型场景控制器或假 API 作为生产验收证据。

**Design:** [test matrix](../../../../tech/qa-pipeline/test-matrix.md)
