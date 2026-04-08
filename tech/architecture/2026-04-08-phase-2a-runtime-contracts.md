# xRag Phase 2A Runtime Contracts

**日期：** 2026-04-08  
**版本：** `v4 / Phase 2A`  
**状态：** freeze-ready  
**适用范围：** `P0-01 ~ P0-07` 实现冻结基线  
**对应文档：**
- [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)
- [Phase 2A Implementation Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
- [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
- [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
- [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)

---

## 1. 文档目的

这份文档用于把 `Phase 2A / P0` 在进入实现 freeze 前必须固定的运行时约束写清楚。

它覆盖三类内容：

1. 数据库运行基线
2. provider / env contract
3. 多队列 runtime contract

它不负责写 schema migration 或 API handler，而是给实现阶段一个不可随意改名的 runtime 边界。

---

## 2. 当前结论

### 2.1 已固定的实现目标

1. 数据库目标基线采用 `pgvector/pgvector:pg16`
2. provider 采用内部抽象 + OpenAI-compatible HTTP
3. worker 采用单服务、多队列注册
4. API 与 worker 共享队列命名 contract

### 2.2 当前仍未落地到代码

- `docker-compose.yml` 与 `deploy/compose/stack.compose.yml` 已切换到目标数据库镜像
- `apps/api` 与 `apps/worker` 的 env loader 已扩展到 `Phase 2A` contract
- `deploy/env/*.example` 已写入新增变量
- 当前尚未落地的是多队列 handler、本体 provider 调用与 `vector` migration

这意味着本文已经进入 repo 配置层，但仍不是完整实现完成态。

---

## 3. 数据库运行基线

### 3.1 目标基线

`Phase 2A` 的目标数据库基线固定为：

```text
pgvector/pgvector:pg16
```

### 3.2 选择原因

1. 已通过本地容器级 SQL 验证，可执行 `CREATE EXTENSION vector`
2. 与当前 `PostgreSQL 16` 主版本保持一致，避免无关升级
3. 比“继续使用 `postgres:16-alpine` 再额外装扩展”更直接，减少部署脚本分叉

### 3.3 实现约束

1. migration 必须先执行：
   - `CREATE EXTENSION IF NOT EXISTS vector`
2. 本地、staging、production 应保持同一数据库镜像族，不允许一个环境有 `vector`、另一个环境没有
3. production 切换仍需在受控发布窗口内执行，不得无验证直接替换

### 3.4 回滚要求

数据库基线切换进入实现时，必须同时准备：

1. 切换失败时回退到 `Phase 1C` compose 基线的步骤
2. 针对 `vector` extension 未成功创建的 smoke 检查

---

## 4. Provider Contract

### 4.1 设计原则

1. `P0` 不引入 vendor SDK
2. `P0` 不做多模型路由
3. provider secret 不进入前端
4. provider 调用默认在 worker 中执行，而不是在 API 请求线程中执行

### 4.2 内部抽象

实现阶段应提供两类内部接口：

- `EmbeddingProvider`
- `AnswerProvider`

两者都走 OpenAI-compatible HTTP 协议，但不得把外部字段命名直接泄漏进上层 domain contract。

### 4.3 正式 env 命名

以下变量名在 `Phase 2A / P0` 进入实现 freeze 后不得再随意改名：

| 变量 | 消费方 | 说明 |
| --- | --- | --- |
| `EMBEDDING_PROVIDER_BASE_URL` | `worker` | embedding provider 基础地址 |
| `EMBEDDING_PROVIDER_API_KEY` | `worker` | embedding provider 鉴权 |
| `EMBEDDING_MODEL` | `worker` | 首版固定 embedding model |
| `EMBEDDING_TIMEOUT_MS` | `worker` | embedding 超时 |
| `ANSWER_PROVIDER_BASE_URL` | `worker` | answer provider 基础地址 |
| `ANSWER_PROVIDER_API_KEY` | `worker` | answer provider 鉴权 |
| `ANSWER_MODEL` | `worker` | 首版固定 answer model |
| `ANSWER_TIMEOUT_MS` | `worker` | answer 超时 |
| `AI_PROVIDER_MAX_RETRIES` | `worker` | provider 请求最大重试次数 |

### 4.4 不进入 env 的内容

以下内容不建议做成可随意切换的 env：

- embedding 维度
- `scope` 模式枚举
- citation validator 行为开关

原因是这些内容会直接影响 schema、answer 语义或评估口径，属于代码与 contract 事实，不属于运行时调参。

### 4.5 secret 边界

1. provider API key 只进入 `worker` 运行时
2. `api` 不直接持有 provider secret
3. 示例 env 文件可以写变量名，但不能提交真实 key

---

## 5. 多队列 Runtime Contract

### 5.1 正式队列名

以下队列名在 `Phase 2A / P0` 进入实现 freeze 后固定：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DOCUMENT_PROCESSING_QUEUE_NAME` | `document-processing` | parse / reparse / ocr / fetch / projection |
| `DOCUMENT_INDEXING_QUEUE_NAME` | `document-indexing` | chunk / embed / reindex |
| `ANSWER_ORCHESTRATION_QUEUE_NAME` | `answer-orchestration` | retrieval / synthesis / citation validation |

### 5.2 并发变量

以下并发变量在实现 freeze 后固定命名：

| 变量 | 说明 |
| --- | --- |
| `DOCUMENT_PROCESSING_CONCURRENCY` | 文档处理并发 |
| `DOCUMENT_INDEXING_CONCURRENCY` | 索引并发 |
| `ANSWER_ORCHESTRATION_CONCURRENCY` | 问答并发 |
| `WORKER_NAME` | worker 实例名 |

当前不在本阶段冻结具体默认数值，只冻结命名和含义。

### 5.3 注册模式

`P0` 固定采用：

- 一个 `worker` service
- 一个进程内注册三类 BullMQ `Worker`
- 每个队列拥有自己的 `QueueEvents`
- 每个队列允许独立并发和错误日志

### 5.4 API 与 Worker 的职责边界

- `api`
  - 负责创建 session、入队、读取状态
  - 不直接执行 embedding 或 answer provider 调用
- `worker`
  - 负责执行 `document-processing`
  - 负责执行 `document-indexing`
  - 负责执行 `answer-orchestration`

---

## 6. 与当前代码的映射要求

当前代码从单队列走向多队列时，应满足：

1. `apps/api/src/config/env.ts`
   - 已从 `queueName` 扩展为显式三队列命名
2. `apps/worker/src/config/env.ts`
   - 已新增 provider env 与三队列并发/env
3. `apps/api/src/queue/*`
   - 不能再只维护一个 `Queue` 实例
4. `apps/worker/src/queue/*` 与 `bootstrap.ts`
   - 不能再只注册一个 `Worker`
5. `deploy/env/*.example`
   - 已同步改成新命名

---

## 7. 本阶段明确不做

1. 不在当前阶段接入真实 provider SDK
2. 不在当前阶段把 worker 拆成多套独立服务
3. 不在当前阶段引入更细粒度的 per-queue retry env
4. 不在当前阶段提前实现多轮对话或多模型路由

这些动作都应等到实现 freeze 后，配合实际代码与 migration 一起落地。

---

## 8. 进入实现 freeze 的 gate

当前进入实现 freeze 的前置 gate 已满足：

1. compose / deploy 基线已切到支持 `vector` 的数据库形态
2. provider/env contract 已写入 config 与 deploy 示例
3. 多队列命名已写入 config 与 queue 设计

接下来的 gate 只剩：

4. `schema / shared-types / API contract / 状态机` 与本文保持一致
