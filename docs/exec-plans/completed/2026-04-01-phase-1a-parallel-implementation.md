# Phase 1A Parallel Implementation

## 1. Metadata

- `plan_id`: `phase-1a-parallel-implementation`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [Current Handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v1 / Phase 1A Status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md), [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)

## 2. Objective

在冻结 `v1 / Phase 1A` 上游 contract 的前提下，并行完成 `upload/storage`、`worker pipeline`、`web integration` 和 `testing/ci`。

## 3. Scope

### In Scope

- 收口 API 持久化基线并冻结 `shared-types`、OpenAPI、API client
- 接入对象存储 presigned upload、对象存在性校验和读取接口
- 接入 BullMQ job enqueue 与 worker 消费
- 打通 `Inbox / Search / Detail` 三页真实数据流
- 建立 API integration、worker、E2E、smoke 最小测试基线

### Out Of Scope

- AI 问答与语义搜索
- OCR
- 多租户
- 移动端

## 4. Assumptions

- 单用户模型继续沿用
- 对象存储默认使用本地 MinIO 与 S3-compatible API
- 文本型 `txt / md / text-based pdf` 作为首批支持格式

## 5. Risks

- queue 与数据库不是强事务，enqueue 失败时需要清晰的失败态回写
- web lane 若越界改 contract，会造成多 lane 冲突
- 测试基线引入后会暴露更多现有骨架问题，需预留收口时间

## 6. Plan

1. 主线程冻结 `schema / shared-types / OpenAPI / api-client / storage / queue` 抽象
2. 并行推进 `upload/storage`、`worker`、`web`、`testing/ci`
3. 主线程按 `storage -> worker -> web -> tests` 顺序集成
4. 更新版本状态、技术文档和验证记录

## 7. Validation

- 单元测试：新增 `api-client`、worker 或工具层的最小单测
- 集成测试：至少覆盖文档创建、上传完成、job 查询
- E2E / smoke：至少覆盖 `Inbox -> Search -> Detail`

## 8. Rollback

- 若 storage/queue 接入不稳定，可保留 schema 与 contract，回退具体实现到 stub
- 若 web 联调不稳定，可暂时保留真实 API client 和接口，但回退页面交互层

## 9. Decision Log

- `2026-04-01`: 主线程先冻结 `shared-types`、API client、storage/queue 抽象，再启动并行 lane
- `2026-04-01`: 并行 lane 不允许擅自修改 API 路径、字段命名和状态枚举
- `2026-04-01`: presigned upload、BullMQ enqueue、worker 最小解析、Inbox/Search/Detail 真实数据流、API integration 和 Playwright smoke 已全部本地验收通过
