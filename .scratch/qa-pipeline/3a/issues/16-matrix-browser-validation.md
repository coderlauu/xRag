# 16 — 矩阵执行与双界面浏览器验收

**What to build:** 执行完整 3A 自动化和两页手工矩阵，回写真实证据；修复实现缺陷但不改规格把失败变通过。

**Blocked by:** 01~15
**Status:** complete

## Slice

- 执行 backend verify、frontend tests/build/lint 和测试报告核对。
- 在 1440/720/375px 走查问答 A 的 8 个、意图树 C 的 8 个场景。
- 记录浏览器、commit、配置、日期、结果和无敏感截图。
- failed/blocked 建工单并保留状态；范围变化回 Phase 3 计划 Decision Log。

## Acceptance

- [x] 测试矩阵所有 `A` 有测试落点与执行证据，所有 `M` 有实际走查记录。
- [x] 3A-UI-01~16 与 3A-E2E-08 如实回写 passed/failed/blocked。
- [x] 项目所有者完成 Step D 浏览器演示确认和 Step E 结果确认后才关闭。
- [x] 不使用原型场景控制器或假 API 作为生产验收证据。

**Design:** [test matrix](../../../../tech/qa-pipeline/test-matrix.md)

## Evidence（2026-08-08）

矩阵 146 条全部回写，无一条留在 `P`。

- **130 条 `A`**：逐条核对落点后回写 passed。112 条由方法名前缀机械对上，18 条人工核对
  （CFG 全组用中文方法名、无 ID 前缀，映射见矩阵 §15.3）。执行证据为后端 508 项 0 失败、
  前端 51 项通过、全程无收费 Key。
- **16 条 `M`**：项目所有者本人在 Chrome + 1440/720/375px、**真实 DashScope Key** 下走查，
  16 条全部 passed，无 failed/blocked、无偏离裁决。详见矩阵 §16。
- 走查用真实模型而非假 Provider，因此不违反"不使用原型场景控制器或假 API 作为验收证据"。

核对过程揪出一个真实缺口并已修复：`3A-API-05`「跨归属 turn 返回 404」此前无任何断言。

证据边界如实记录：手工走查无截图归档，形式是所有者确认；且真实模型链路不可重放，
回归仍以 130 条自动化用例为准。
