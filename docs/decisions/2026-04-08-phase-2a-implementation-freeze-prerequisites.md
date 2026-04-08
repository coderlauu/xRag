# Phase 2A Implementation Freeze Prerequisites

**日期：** 2026-04-08  
**版本：** `v4 / Phase 2A`  
**状态：** 已验证  
**适用范围：** `P0-01 ~ P0-07`  
**对应文档：**
- [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
- [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
- [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- [Deploy README](/Users/coderlauu/xRag/deploy/README.md)

---

## 1. 本文档目的

这份文档用于验证 `Phase 2A` 进入实现 freeze 前最关键的三类前置条件：

1. 数据库基线是否具备 `pgvector` 能力
2. provider / env contract 是否已有历史包袱
3. 多队列配置是否已在当前工程中具备落点

它不负责冻结 schema 或 API，而是给出一个更基础的结论：

`当前仓库是否已经具备进入实现 freeze 的现实前提。`

---

## 2. 验证范围

本次验证只覆盖：

- `docker-compose.yml`
- `deploy/compose/stack.compose.yml`
- `deploy/env/*.example`
- `apps/api/src/config/env.ts`
- `apps/worker/src/config/env.ts`
- `apps/api/src/queue/*`
- `apps/worker/src/queue/*`
- `apps/worker/src/worker/bootstrap.ts`
- `apps/api/package.json`
- `apps/worker/package.json`

以及一次本地容器级 SQL 验证。

---

## 3. 验证结论总览

| 前置条件 | 当前结论 | 状态 |
| --- | --- | --- |
| `pgvector` 数据库基线 | repo runtime 基线已切到 `pgvector/pgvector:pg16`，并通过本地 compose 级 `CREATE EXTENSION vector` 验证 | ready-for-freeze |
| provider / env contract | env loader、runtime contract 与 deploy env 示例已同步到首版命名 | ready-for-freeze |
| 多队列配置结构 | 命名与并发 contract 已进入 config 层，当前仍保留单队列行为兼容实现 | ready-for-freeze |

结论：

- `Phase 2A` 的 runtime freeze prep 已完成。
- 当前已没有实现 freeze 前的硬 blocker。
- provider/env 与多队列部分的正式命名与边界已写入 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)。

---

## 4. 数据库基线验证

### 4.1 当前 repo 事实

当前本地与部署编排都已切到：

- `pgvector/pgvector:pg16`

这意味着仓库事实层面已经显式声明 `pgvector` 运行基线。

### 4.2 容器级验证

已完成以下验证：

1. 在 `postgres:16-alpine` 容器中检查 `vector.control` 与 `vector.so`
   - 结果：不存在
2. 在 `postgres:16-alpine` 容器中执行：
   - `CREATE EXTENSION vector;`
   - 结果：失败，报错 `extension "vector" is not available`
3. 在 `pgvector/pgvector:pg16` 容器中检查 `vector.control` 与 `vector.so`
   - 结果：存在
4. 在 `pgvector/pgvector:pg16` 容器中执行：
   - `CREATE EXTENSION vector; SELECT extname FROM pg_extension WHERE extname = 'vector';`
   - 结果：成功，返回 `vector`

### 4.3 结论

- `pgvector/pgvector:pg16` 已通过容器级 SQL 验证，也已通过当前 `docker-compose.yml` 的 compose 级 `CREATE EXTENSION vector` 验证。
- 数据库运行基线已满足 `Phase 2A` 的 `pgvector` 前置要求。

### 4.4 实现 freeze 前必须完成的动作

1. 在 `Phase 2A` 进入 schema freeze 时，把以下语句正式写入 migration：
   - `CREATE EXTENSION IF NOT EXISTS vector`
2. 明确 production 回滚策略：
   - 若切换镜像失败，如何回到 `Phase 1C` 基线

以上动作已不再构成实现 freeze blocker，而是后续 schema/migration 实施要求。

---

## 5. Provider / Env Contract 验证

### 5.1 当前 repo 事实

本次检查发现：

- `apps/api` 与 `apps/worker` 当前没有任何 AI provider SDK 依赖
- `apps/worker` env loader 已补齐 `embedding / answer / api key / base url / timeout / model` 相关字段
- deploy env 示例已补齐 provider 变量占位

这说明当前仍然没有历史兼容负担，也已经可以按新命名直接进入实现。

### 5.2 推荐冻结的首版 env contract

建议在进入实现 freeze 前固定以下变量。正式命名已写入 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)：

| 变量 | 用途 |
| --- | --- |
| `EMBEDDING_PROVIDER_BASE_URL` | embedding provider 的 OpenAI-compatible base URL |
| `EMBEDDING_PROVIDER_API_KEY` | embedding provider 鉴权 |
| `EMBEDDING_MODEL` | 首版固定 embedding model |
| `EMBEDDING_TIMEOUT_MS` | embedding 请求超时 |
| `ANSWER_PROVIDER_BASE_URL` | answer provider 的 OpenAI-compatible base URL |
| `ANSWER_PROVIDER_API_KEY` | answer provider 鉴权 |
| `ANSWER_MODEL` | 首版固定 answer model |
| `ANSWER_TIMEOUT_MS` | answer 请求超时 |
| `AI_PROVIDER_MAX_RETRIES` | provider 调用最大重试次数 |

### 5.3 结论

- provider / env contract 已具备进入实现 freeze 的条件。
- 当前不需要为兼容旧实现而保留额外别名或临时变量。

---

## 6. 多队列配置验证

### 6.1 当前 repo 事实

当前工程已完成多队列命名进入 config 层：

- env loader 已补齐 `DOCUMENT_PROCESSING_QUEUE_NAME / DOCUMENT_INDEXING_QUEUE_NAME / ANSWER_ORCHESTRATION_QUEUE_NAME`
- worker env 已补齐对应并发变量
- `deploy/env/*.example` 已切到新命名
- 当前运行逻辑仍只消费 `document-processing`，保持行为兼容

### 6.2 这意味着什么

这意味着：

- 多队列 contract 已经正式冻结到 config 层
- 多队列真实 handler 注册仍留待实现阶段

### 6.3 推荐冻结的首版队列 contract

建议在实现 freeze 前固定以下变量与语义。正式命名已写入 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)：

| 变量 | 用途 |
| --- | --- |
| `DOCUMENT_PROCESSING_QUEUE_NAME` | 现有 parse / ocr / fetch / projection 队列 |
| `DOCUMENT_INDEXING_QUEUE_NAME` | 新增 chunk / embed 队列 |
| `ANSWER_ORCHESTRATION_QUEUE_NAME` | 新增 retrieval / synthesis 队列 |
| `DOCUMENT_PROCESSING_CONCURRENCY` | 文档处理并发 |
| `DOCUMENT_INDEXING_CONCURRENCY` | 索引并发 |
| `ANSWER_ORCHESTRATION_CONCURRENCY` | 问答并发 |
| `WORKER_NAME` | 当前 worker 实例名，继续保留 |

### 6.4 结论

- 多队列 contract 已具备进入实现 freeze 的条件。
- 当前剩余工作是把 `document-indexing` 与 `answer-orchestration` 真正实现出来，而不是继续讨论命名。

---

## 7. 对实现 freeze 的判断

当前判断如下：

1. runtime freeze prep 已完成
2. `schema / shared-types / API contract` 现在可以进入正式实现 freeze
3. 后续重点应转到 schema、shared types、queue handlers 与 answer contract 实现

也就是说，当前阶段最合理的下一步是：

- 从 planning/design 正式进入 implementation freeze
- 主线程先冻结：
  - `schema`
  - `shared-types`
  - `API contract`
  - `状态机 / citation / scope / eval contract`

---

## 8. 下一步建议

1. 以 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md) 为基线进入实现 freeze
2. 先冻结 `schema / shared-types / API contract / 状态机`
3. 再拆 `worker / web / test` 等实现 lane
