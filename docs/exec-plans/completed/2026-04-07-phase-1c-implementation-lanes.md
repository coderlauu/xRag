# Phase 1C Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-1c-implementation-lanes`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v3 handoff](/Users/coderlauu/xRag/docs/handoff/v3.md), [v3 status](/Users/coderlauu/xRag/docs/status/v3-phase-1c.md), [Phase 1C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-1c-prd.md), [v3 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md), [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md), [Phase 1C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md), [Phase 1C API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)

## 2. Objective

在 `Phase 1C` 的原型、交互说明和增量技术方案冻结后，按主线程优先、子 agent 并行的方式推进 OCR、链接抓取、搜索增强和运维可见性。

## 3. Main Thread First

实现前，主线程必须先冻结：

1. `schema`
2. `shared-types`
3. `API contract`
4. `diagnosis code`
5. `document processing event` 事件模型

未完成这一步前，不启动并行编码。

## 4. Lanes

### Lane A: OCR Pipeline

- 扫描件识别与 OCR 适配层
- OCR 任务状态推进
- OCR 失败诊断码
- OCR 相关单测 / 集成测试

### Lane B: Link Ingest

- `POST /documents/link`
- 抓取 HTML
- 正文提取与失败诊断
- 链接详情投影与回归测试

### Lane C: Search Explain & Timeline

- 搜索解释与排序增强字段接入
- 详情页时间线读取
- 搜索页 / 详情页交互收口
- E2E 覆盖新交互

### Lane D: Ops & Observability

- OCR / 抓取器 / 投影的运维摘要
- 时间线事件聚合
- 降级与回滚展示
- smoke 与 incident 视角补强

## 5. Integration Order

1. 主线程冻结上游 contract
2. 合并 `Lane A` 与 `Lane B`
3. 再合并 `Lane C`
4. 最后合并 `Lane D`
5. 主线程统一跑验证、更新状态和文档

## 6. Validation

- `pnpm validate`
- 新增 `OCR / link ingest` integration tests
- 新增搜索解释和时间线的 E2E
- deploy / smoke 保持绿灯

## 7. Risks

- 若 OCR 与链接抓取同时改 contract，容易造成 worker 和前端都返工
- 若先写页面再补事件模型，时间线容易成为临时拼装逻辑
- 若排序解释放在前端拼装，搜索页和详情页会出现不一致

## 8. Decision Log

- `2026-04-07`: 主线程先冻结 `schema / shared-types / API contract / diagnosis code / document processing event`，再按 `OCR / link ingest / search explain & timeline / ops` 四条 lane 推进实现
- `2026-04-07`: GitHub Actions `24081424992` 已成功完成 `infra / validate / integration / e2e / build-images / deploy-production / smoke-production`，`Phase 1C` 正式关闭，本计划移入 `completed`
- `2026-04-07`: 生产运维入口补齐 `https://db.xrag.coderlau.cn` 的 `pgweb` 管理台，以及 PostgreSQL `127.0.0.1:5432` 回环映射，供 `Navicat / TablePlus / DBeaver` 通过 `SSH Tunnel` 访问
