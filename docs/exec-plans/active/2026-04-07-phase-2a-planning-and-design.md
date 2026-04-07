# Phase 2A Planning and Design

## 1. Metadata

- `plan_id`: `phase-2a-planning-and-design`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v4 handoff](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A prototype](/Users/coderlauu/xRag/prototype/v4/index.html), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md), [v3 status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)

## 2. Objective

在统一导入、检索与可观测性基线上，引入可引用的 AI 问答与混合检索，让资料从可找回走向可直接复用。

## 3. Scope

### In Scope

- 明确 `Phase 2A` 的产品目标、信任边界与非目标
- 产出 `PRD / handoff / status / planning exec plan / interaction spec / prototype / evaluation plan`
- 定义问答输入、范围控制、答案引用、拒答与追溯交互
- 定义混合检索、范围控制、引用、拒答、索引 freshness 的产品边界
- 定义评估、上线门槛、拒答原则与运维观察面
- 为后续技术方案设计准备问题清单，而不是在本阶段直接冻结 `architecture / data model / api`

### Out Of Scope

- 直接进入 AI 问答正式编码
- 在产品与交互边界未稳前提前冻结 `architecture / data model / api`
- 跳过评估和引用 contract 直接接模型出答案
- 多用户协作 / 权限模型
- 浏览器插件
- 移动端
- 更大范围的动态抓取与反反爬能力

## 4. Assumptions

- `Phase 1C` 已完成并归档，当前统一导入、关键词搜索、OCR、链接抓取、时间线与运维基线可复用
- `Phase 2A` 的第一优先级是建立“可信问答层”的产品与交互边界，不是继续扩张导入渠道
- 首期可继续按单用户、私有知识库基线规划
- 规划阶段应先冻结问题边界和评估口径，再决定是否进入正式技术方案设计

## 5. Risks

- 如果先写 AI 功能再补引用和拒答 contract，产品信任会迅速失稳
- 如果问答与搜索不共享统一事实源，搜索页和问答页会出现两套产品语义
- 如果没有固定评估集，后续无法判断“答得更好”还是只是“答得更多”
- 如果不先定义成本和延迟预算，后续技术设计会在上线前失控

## 6. Plan

1. 冻结 `Phase 2A` 的产品目标、问题边界、P0 / P1 和信任原则
2. 冻结问答交互：问题输入、scope contract、答案卡片、引用、拒答、跳转与历史
3. 冻结产品 trust contract：scope、citation、refusal、freshness 与 evidence trace 的统一语义
4. 冻结评估方案：golden questions、检索指标、groundedness rubric、延迟与成本预算
5. 形成进入技术方案设计前的问题清单、决策阈值与非目标
6. 在以上边界稳定后，再决定是否进入技术方案设计与后续实现 lane

## 7. Validation

- 单元测试：当前阶段不要求
- 集成测试：当前阶段不要求
- E2E / smoke：当前阶段不要求；以版本资产完整性、文档一致性和后续实现可拆分性为准

## 8. Rollback

- 若 `Phase 2A` 目标过大或信任边界不清，可回退为“先做 semantic retrieval + answer-readiness，不在本轮交付完整生成式问答”

## 9. Decision Log

- `2026-04-07`: 新需求按 `new-version` 处理，启动 `v4 / Phase 2A`
- `2026-04-07`: `Phase 2A` 的主线定义为 `Grounded AI Retrieval & Answering`
- `2026-04-07`: 本轮先做 `PRD / 交互 / 原型 / 评估方案` 冻结，不直接进入编码
- `2026-04-07`: 当前仍停留在产品调研与需求冻结阶段，`architecture / data model / api` 将推迟到后续技术方案设计节点
