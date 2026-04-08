# Phase 2A Planning and Design

## 1. Metadata

- `plan_id`: `phase-2a-planning-and-design`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v4 handoff](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [Phase 2A technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md), [Phase 2A implementation freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A prototype](/Users/coderlauu/xRag/prototype/v4/index.html), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md), [v3 status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md)

## 2. Objective

在统一导入、检索与可观测性基线上，引入可引用的 AI 问答与混合检索，让资料从可找回走向可直接复用。

## 3. Scope

### In Scope

- 明确 `Phase 2A` 的产品目标、信任边界与非目标
- 产出 `PRD / handoff / status / planning exec plan / interaction spec / prototype / evaluation plan`
- 定义问答输入、范围控制、答案引用、拒答与追溯交互
- 定义混合检索、范围控制、引用、拒答、索引 freshness 的产品边界
- 定义评估、上线门槛、拒答原则与运维观察面
- 完成需求细化与优先级拆分，明确 `P0 / P1 / deferred` 与阶段切片
- 在用户确认进入技术方案评估后，产出 `P0-only architecture / data model / api` draft
- 收敛 `P0` 关键 tradeoff，并把进入实现 freeze 前的前置条件写进正式决策文档

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
5. 完成需求细化与优先级拆分，形成 `可信问答闭环 -> Freshness / Eval -> 范围与体验补强` 的阶段顺序
6. 围绕 `P0` 关键 tradeoff 产出技术评估稿：向量层、分队列、answer orchestration、scope snapshot、citation validator
7. 形成进入实现 freeze 前的前置条件、决策阈值与非目标
8. 在以上边界稳定后，再决定是否进入实现 freeze 与后续实现 lane

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
- `2026-04-07`: 已将 `Phase 2A` 细化为 `P0 可信问答闭环 / P1 范围与运维补强 / deferred`，后续技术方案默认只围绕 `P0` 展开
- `2026-04-07`: 已补齐独立 backlog 文档，把 `P0-01 ~ P0-07` 细化为逐项验收清单，供后续技术方案和 coding handoff 直接引用
- `2026-04-07`: 用户确认进入技术方案评估阶段，已补齐 `P0-only architecture / data model / api` draft，并给出关键 tradeoff 推荐路径
- `2026-04-08`: 已补独立 `technical tradeoffs` 决策文档，收敛 `pgvector / 多队列 / provider contract / scope snapshot / reindex / 202 Accepted` 等关键方向，下一步转入实现 freeze 前置条件验证
- `2026-04-08`: 已完成实现 freeze 前置条件验证，明确 `provider/env` 与多队列 contract 可冻结，并确认 `pgvector/pgvector:pg16` 作为目标数据库基线
- `2026-04-08`: 已补独立 `runtime contracts` 文档，并把数据库基线、env loader、deploy env 示例与 compose 配置同步到 freeze prep 形态；当前可进入 `schema / shared-types / API contract / 状态机` 冻结
- `2026-04-08`: planning/design 阶段正式关闭，并移交到独立的 implementation freeze exec plan
