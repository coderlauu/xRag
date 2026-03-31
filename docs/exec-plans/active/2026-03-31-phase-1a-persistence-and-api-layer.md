# Phase 1A Persistence And API Layer

## 1. Metadata

- `plan_id`: `phase-1a-persistence-and-api-layer`
- `status`: `active`
- `owner`: `codex`
- `classification`: `medium-feature`
- `related_docs`: [Current Handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)

## 2. Objective

把 `apps/api` 从 sample-data stub 演进为可落 PostgreSQL 的正式 API 基线，并提交首版 Drizzle schema 与 migration。

## 3. Scope

### In Scope

- 在 `apps/api` 落 `Drizzle ORM` schema、config、migration、数据库接线
- 基于 `documents / tags / document_tags / uploads / document_parse_jobs` 实现 repository 层
- 把 `documents / uploads / tags / jobs` 模块重构为 `controller -> service -> repository`
- 让现有 REST 接口改为数据库读写
- 输出更新后的 OpenAPI 产物与前端 SDK

### Out Of Scope

- Worker 真正消费数据库任务并推进状态
- 对象存储真实直传与签名
- OCR、文本抽取、搜索排序优化
- 完整 integration/e2e 测试基线

## 4. Assumptions

- 当前仍按单用户场景实现，`owner_id` 暂保留但不启用真实多租户逻辑
- `uploads/complete` 与 `documents/retry` 先以数据库任务记录为事实来源，队列消费在后续阶段补齐
- 搜索先落可靠筛选与 `search_text` 查询，`tsvector`/`pg_trgm` 基础设施本轮一并建好

## 5. Risks

- Drizzle schema 与 tech data model 若出现偏差，会放大后续 worker/search 返工成本
- 先落数据库事实源但未接 worker，可能让部分状态流仍停留在 `pending/queued`
- OpenAPI 产物若没有跟代码同步生成，前端 SDK 会再次漂移

## 6. Plan

1. 补齐数据库与 API 环境配置、Drizzle schema、migration 与生成脚本
2. 引入 repository 层，把 sample-data 替换为真实 PostgreSQL 读写
3. 重构 `documents / uploads / tags / jobs` 模块为 `controller -> service -> repository`
4. 生成 OpenAPI 产物并更新 `packages/api-client`
5. 用 compose Postgres 跑 migration，并执行 typecheck/build/validate

## 7. Validation

- 单元测试：当前仓库未搭测试基线，本轮以 typecheck/build 与手动 API smoke 为主
- 集成验证：对本地 compose Postgres 执行 migration，并验证 API 可启动
- Contract 验证：生成 OpenAPI 文件并同步 SDK

## 8. Rollback

- 若 schema 设计有误，可回退本轮 migration 与 repository 重构，恢复 stub controller
- 若 OpenAPI/SDK 生成链不稳定，可暂保留生成产物但回退自动生成脚本

## 9. Decision Log

- `2026-03-31`: 本任务归类为 `medium-feature`，不创建新 handoff 版本
- `2026-03-31`: 先让数据库成为 API 的事实来源，worker 状态推进留到下一阶段
