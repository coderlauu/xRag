# Phase 1B Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-1b-implementation-lanes`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md), [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md), [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)

## 2. Objective

把 `Phase 1B` 从“文档、原型、技术方案已冻结”推进到“按主线程 + 子 agent lane 稳定落地实现”。

## 3. Scope

### In Scope

- 先由主线程冻结 `schema / shared-types / API contract / diagnosis code`
- 再按 lane 并行推进 `multipart / pdf parse / frontend diagnostics / ops`
- 明确每条 lane 的写入边界、依赖和验收标准
- 规定本轮集成、验证和收口顺序

### Out Of Scope

- 跳过主线程 contract 冻结直接多线开工
- 无边界的全自动 agent 自主改 API
- OCR、语义搜索、多租户等 `Phase 1B` 范围外能力

## 4. Assumptions

- `Phase 1B` 的 PRD、prototype、interaction spec 和 tech docs 已齐备
- 当前生产基线可用，但上传链路和本地 Docker 集成证据仍需持续补强
- 本轮并行开发默认采用 `git worktree + 子 agent`

## 5. Risks

- 若主线程未先冻结 contract，lane 间会互相返工
- multipart 与 pdf parse 都会碰状态机，若状态枚举漂移会直接影响前后端联调
- ops lane 若过早接入生产化细节，容易反向阻塞主功能实现

## 6. Plan

1. 主线程冻结上游事实源
2. 按写入边界拆 4 条实现 lane
3. 按 `后端主链路 -> 前端联调 -> ops 收口` 顺序集成
4. 用 integration / e2e / smoke 做逐层验收

### 6.1 Main Thread First

主线程必须先完成：

1. `schema` 冻结
   - `documents.upload_status`
   - `documents.diagnosis_code`
   - `documents.diagnosis_summary`
   - `uploads` 的 multipart 字段
   - `upload_parts`
2. `shared-types` 冻结
   - 上传状态枚举
   - 解析状态枚举
   - diagnosis code 枚举
   - ops 读取 DTO
3. `API contract` 冻结
   - `uploads/initiate`
   - `uploads/{id}/parts`
   - `uploads/{id}/parts/{partNumber}/complete`
   - `uploads/{id}/complete`
   - `documents list/detail/retry`
   - `ops/health-summary`
   - `ops/incidents`
   - `ops/deployments/latest`
4. OpenAPI / SDK 收口
   - OpenAPI 生成产物
   - `packages/api-client`

只有这四项完成后，才允许子 agent 并行实现。

### 6.2 Lane A: Multipart Upload

负责范围：

- `apps/api`
  - upload session
  - multipart presign
  - part complete
  - complete 校验
- `storage` 适配器

写入边界：

- `apps/api/src/uploads/**`
- `apps/api/src/storage/**`
- 必要 migration

禁止修改：

- 共享状态枚举命名
- 文档详情响应字段语义

验收标准：

- single / multipart 两种 initiate 都可返回正确结构
- complete 幂等
- object missing 能返回稳定错误码

### 6.3 Lane B: PDF Parse Worker

负责范围：

- `apps/worker`
  - 文本型 PDF 提取
  - 失败分类
  - job / document 状态推进
- `apps/api` 中与 queue 绑定的最小增量

写入边界：

- `apps/worker/**`
- `apps/api/src/jobs/**`
- `apps/api/src/queue/**`

禁止修改：

- upload contract
- web 页面字段命名

验收标准：

- 文本型 PDF 可进入 `success`
- 扫描版 PDF 进入 `pdf_parse_unsupported`
- retry parse 可重新入队并回写状态

### 6.4 Lane C: Frontend Diagnostics

负责范围：

- `apps/web`
  - Inbox 上传工作台
  - Search 诊断筛选
  - Detail 上传会话与失败展示
  - Ops Board 读接口接入

写入边界：

- `apps/web/**`
- 只消费 `packages/api-client`

禁止修改：

- API 路径
- DTO 结构
- 状态枚举

验收标准：

- `Inbox -> Search -> Detail` 主链路打通
- 失败诊断、重试动作、ops 摘要可见
- URL state 可承接状态与诊断筛选

### 6.5 Lane D: Ops / Observability

负责范围：

- 健康摘要接口
- incidents 聚合读取
- deployments latest 摘要
- 结构化日志和 health checks 补强

写入边界：

- `apps/api/src/health/**`
- `apps/api/src/ops/**`
- `apps/worker` logging / metrics 最小集
- 必要 CI / deploy 只读增强

禁止修改：

- 上传主链路 contract
- 前端核心页面行为

验收标准：

- Ops Board 的最小读接口可用
- deploy / incident / health 三类信息有统一入口

### 6.6 Integration Order

集成顺序固定为：

1. 主线程合并 contract / schema
2. 合并 `Lane A`
3. 合并 `Lane B`
4. 合并 `Lane C`
5. 合并 `Lane D`
6. 主线程统一跑验证并更新 status

## 7. Validation

- 单元测试：
  - upload session builder
  - diagnosis 映射
  - pdf parser adapter
- 集成测试：
  - initiate / complete
  - multipart complete
  - pdf parse success / unsupported
  - retry parse
- E2E / smoke：
  - 上传 PDF -> 搜索命中 -> 详情查看
  - multipart 中断恢复
  - Ops Board 读取成功

## 8. Rollback

- 若 contract 冻结后某 lane 造成集成失稳，先回滚该 lane，不回退全部 `Phase 1B` 资产
- migration 必须保持向后兼容，确保应用层可先回滚到上一稳定镜像
- 若 ops lane 影响生产可用性，可先隐藏 Ops Board 或禁用对应只读接口

## 9. Decision Log

- `2026-04-06`: `Phase 1B` 先冻结 contract，再用 `multipart / pdf parse / frontend diagnostics / ops` 四条 lane 并行实现
