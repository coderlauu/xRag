# Phase 1C Planning and Design

## 1. Metadata

- `plan_id`: `phase-1c-planning-and-design`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v3.md`, `docs/status/v3-phase-1c.md`

## 2. Objective

在已完成 PDF 与上传稳态的基础上，增强扫描件与链接内容接入能力，并提升检索质量与运营可观测性。

## 3. Scope

### In Scope

- `Phase 1C` 的 PRD、版本状态和 handoff 收口
- `Phase 1C` 原型范围与交互说明定义
- 增量架构、数据模型、API 与状态机设计
- 初版实现 lane 拆分与验证策略

### Out Of Scope

- 直接进入 `Phase 1C` 业务编码
- 修改 `Phase 1B` 已稳定的主链路 contract
- AI 问答、向量检索、浏览器插件等新产品线

## 4. Assumptions

- `Phase 1B` 已完成并归档，当前可以安全切换到新版本规划
- `Phase 1C` 会同时引入 OCR 与链接抓取，因此必须先做边界冻结，再谈并行实现
- 现有 production 基线、CI/CD、hotfix 流程继续沿用

## 5. Risks

- 如果先写代码再补原型和技术方案，OCR 与链接抓取的状态机很容易再次漂移
- 若把 `Phase 1C` 范围一次性放太大，版本会失去可验收边界
- OCR 与链接抓取会引入新的运行时依赖，若不先定义诊断和回滚，生产风险会放大

## 6. Plan

1. 固定 `Phase 1C` 的产品目标、非目标和验收标准
2. 补齐 `prototype / interaction spec / tech docs` 三类增量资产
3. 冻结 `schema / shared-types / API contract / diagnosis code`
4. 基于冻结边界拆出主线程与子 agent lanes
5. 形成可执行的实现计划后，再进入正式开发

## 7. Validation

- 单元测试：当前阶段不要求
- 集成测试：当前阶段不要求
- E2E / smoke：当前阶段不要求；以文档一致性和版本资产完整性为准

## 8. Rollback

- 若 `Phase 1C` 目标定义不清，可回退到仅保留 `handoff / status / prd`，暂停实现计划扩张

## 9. Decision Log

- `2026-04-07`: 将下一阶段正式定义为 `v3 / Phase 1C`，先做规划与设计，不直接进入编码
- `2026-04-07`: 已完成 `PRD / prototype / interaction spec / architecture / data model / api / implementation lanes` 首轮冻结
