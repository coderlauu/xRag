# Phase 1B Planning And Design

## 1. Metadata

- `plan_id`: `phase-1b-planning-and-design`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md), [Phase 1A retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-02-phase-1a-retrospective.md)

## 2. Objective

冻结 `Phase 1B` 的新增 contract，明确 `pdf` 真实解析、multipart 上传和 observability 的实施边界。

## 3. Scope

### In Scope

- 梳理 `Phase 1A` 已有实现与可复用资产
- 明确 `pdf` 真实解析链路
- 明确 multipart 上传链路与 API 变化
- 明确 production 观测与回滚最小集
- 产出下一轮实现前需要冻结的数据模型与接口边界

### Out Of Scope

- 直接进入大规模实现
- 新增 AI 能力
- OCR

## 4. Assumptions

- `Phase 1A` 的主链路、CI/CD 和 production 已稳定可用
- 下一阶段以增量方式演进，不推翻现有架构

## 5. Risks

- 若在规划阶段直接改实现，会再次引入 contract 漂移
- `pdf` 解析库选择会影响 worker 资源模型和部署体积
- multipart 上传若设计不稳，会反向影响 web、api 和 storage 边界

## 6. Plan

1. 基于 retrospective 复盘 `Phase 1A` 的可复用边界与遗留问题
2. 冻结 `pdf`、multipart、observability 的新增需求与技术方案
3. 产出下一轮可并行实施的 lane 划分和验证方案

## 7. Validation

- 单元测试：本阶段不新增
- 集成测试：本阶段不新增
- E2E / smoke：沿用 `Phase 1A` 当前成功基线

## 8. Rollback

- 本阶段为规划文档，不涉及业务回滚

## 9. Decision Log

- `2026-04-02`: `Phase 1A` 已完成并归档，下一阶段聚焦 `pdf` 真实解析、multipart 上传和 production hardening
- `2026-04-06`: `Phase 1B` 的 PRD、prototype、interaction spec、architecture、data model、api design 与 implementation lanes 已落库，本计划完成并归档
