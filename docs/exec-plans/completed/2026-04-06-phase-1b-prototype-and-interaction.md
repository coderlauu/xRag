# Phase 1B Prototype And Interaction

## 1. Metadata

- `plan_id`: `phase-1b-prototype-and-interaction`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md), [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-02-phase-1b-planning-and-design.md)

## 2. Objective

为 `Phase 1B` 补齐可运行原型和交互说明，让 `pdf` 真实解析、multipart 上传、失败诊断与 production 观测具备可评审的页面、状态和流程定义。

## 3. Scope

### In Scope

- 新增 `prototype/v2/` 版本化原型目录
- 覆盖 `Inbox / Search / Detail / Ops` 四类页面
- 明确上传、解析、失败诊断、回滚和 CI incident 的交互状态
- 产出 `Phase 1B` 交互说明文档

### Out Of Scope

- 把原型直接并入正式前端工程
- 真实接口联调
- OCR 和语义搜索的交互深化

## 4. Assumptions

- `Phase 1A` 的正式系统已上线，`Phase 1B` 原型是增量产品资产
- 原型需要重点表达状态流，而不是像真实系统一样接后端

## 5. Risks

- 若不先固定交互细节，后续 `pdf / multipart / observability` 技术方案会继续漂移
- 若只补文档不补原型，失败态和运维态仍会被低估

## 6. Plan

1. 复用 `v1` 原型结构，补齐 `v2` 的页面和 mock 场景
2. 为每个关键状态建立可见反馈和流转路径
3. 落地 `Phase 1B` interaction spec，供技术方案和开发 handoff 使用

## 7. Validation

- 原型目录结构完整，可直接本地打开
- 页面覆盖 `upload -> parse -> search -> diagnose -> observe`
- interaction spec 能逐页解释主路径与异常路径

## 8. Rollback

- 本次仅新增产品资产，不改正式业务代码

## 9. Decision Log

- `2026-04-06`: `Phase 1B` 必须先补原型与交互说明，再进入增量 contract 冻结和实现拆分
- `2026-04-06`: `prototype/v2` 与 `v2 interaction spec` 已完成并进入 repo 基线，本计划完成并归档
