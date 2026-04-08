# Phase 2A P0 Technical Tradeoffs

**日期：** 2026-04-08  
**版本：** `v4 / Phase 2A`  
**状态：** 已决定  
**适用范围：** `P0-01 ~ P0-07`  
**对应文档：**
- [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
- [Phase 2A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md)
- [Phase 2A Implementation Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
- [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
- [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)

---

## 1. 本文档目的

这份文档用于把 `Phase 2A / P0` 技术评估中最影响实现边界的 tradeoff 收敛成明确结论。

它解决的问题不是“代码怎么写”，而是：

- 哪些技术方向已经决定
- 哪些方案被排除
- 实现前还必须先验证什么

后续若进入实现 freeze，`schema / shared-types / API contract / 状态机 / citation / scope / eval contract` 都应以本文和对应 `tech/*` 文档为准。

---

## 2. 当前工程事实

基于当前 repo，`P0` 的 tradeoff 必须建立在以下事实之上：

1. 本地与部署编排当前都以 `postgres:16-alpine` 为数据库基础镜像，尚未显式切到 `pgvector` 发行版。
2. production 已有 `db.xrag.coderlau.cn` 的 `pgweb` 管理台，以及 PostgreSQL `127.0.0.1:5432` 回环映射，数据库扩展、索引和表结构具备排查入口。
3. 当前 `api` 与 `worker` 只围绕单个 `document-processing` 队列设计，尚无问答和索引专用队列。
4. 当前 schema 已有 `documents / document_parse_jobs / document_processing_events`，但没有 `chunk / vector / answer session / retrieval trace` 数据模型。
5. 当前 repo 还没有接入任何 AI provider SDK，首版 provider 选型不应先绑定重型框架。

---

## 3. 已决定事项

### 3.1 向量层采用 `PostgreSQL + pgvector`

**结论**

- `P0` 采用 `PostgreSQL + pgvector`，不引入独立外部向量库。
- 向量、chunk、citation locator 与 retrieval trace 继续放在当前 PostgreSQL 主库。
- 首版只支持单一 embedding 维度，固定为 `1536`。

**原因**

- 当前生产排障链路已经围绕 PostgreSQL 建立，继续同库排查成本最低。
- `P0` 的目标是可信问答闭环，不是大规模召回扩展。
- 外部向量库会把同步、一致性和运维复杂度提前带进来。

**实施约束**

- 进入编码前必须完成 `vector` 扩展可用性验证。
- 若当前镜像无法稳定启用 `vector`，应先调整数据库基线镜像或扩展安装方式，而不是直接改选外部向量库。

### 3.2 Worker 拓扑采用“单服务、多队列”

**结论**

- 继续维持一套 `worker` 服务与镜像。
- 同一进程内注册三类队列 worker：
  - `document-processing`
  - `document-indexing`
  - `answer-orchestration`

**原因**

- `P0` 需要队列隔离，避免 OCR/抓取 backlog 拖慢索引和在线问答。
- 现阶段没有必要把 worker 先拆成多套部署单元。
- 这样既能保留最小基础设施增量，也能让队列级并发和限流独立配置。

**实施约束**

- `api` 和 `worker` 的 env/config 需要从单队列扩展为多队列定义。
- 队列级并发、重试和 dead-letter 策略应独立配置，不得共享一套隐式默认值。

### 3.3 Provider 采用内部抽象 + OpenAI-compatible HTTP

**结论**

- `P0` 先定义内部 `EmbeddingProvider` 与 `AnswerProvider` contract。
- 首版 provider 通过 `fetch` 调用 OpenAI-compatible HTTP API，不先引入 vendor SDK 或 AI orchestration 框架。
- `P0` 固定一个 embedding model 和一个 answer model，不做多模型路由。

**原因**

- 当前 repo 尚未接入任何 provider SDK，直接引入重型框架会把评估范围拉大。
- 先抽象 provider contract，可把后续模型切换成本压到实现细节层。
- `P0` 的重点是 citation、scope、refusal 和 freshness，不是模型调度平台。

**实施约束**

- embedding 维度与模型名必须写入 `index_version` 或等价策略版本中。
- provider timeout、重试与成本记录必须成为正式 contract，而不是临时日志。

### 3.4 `search_result` scope 采用有上限的 snapshot

**结论**

- `search_result` scope 必须同时保存原始 `query / filters / total` 与 `document_ids` snapshot。
- `document_ids` snapshot 在 `P0` 上限固定为 `100`。
- 超过上限时，写入前 `100` 个 eligible documents，并显式记录 `truncated=true`。

**原因**

- 只保存搜索词会导致后续问答结果漂移，无法稳定复现 citation。
- `P0` 需要稳定性优先于“全量自动覆盖”。
- 对单用户私有知识库，`100` 个文档已足以覆盖多数 `search_result -> ask` 使用场景。

**实施约束**

- snapshot 是问答 contract 的一部分，不得在 session 创建后重新动态扩张。
- 当 `truncated=true` 时，前端必须能提示用户当前范围是裁剪后的搜索结果。

### 3.5 `reindex` 作为独立动作保留，并最小开放给用户

**结论**

- `POST /documents/{id}/reindex` 与现有 `retry` 保持分离。
- `P0` 允许普通用户对 `failed / stale / not_indexed` 文档触发 reindex。
- 服务端必须做去重保护，同一文档同一时间只允许一个活跃 reindex。

**原因**

- “解析失败重试”和“问答索引重建”是两类不同语义，混在同一路径会破坏诊断清晰度。
- `P0-06` 需要用户自助回补 freshness，而不仅依赖内部运维。
- 去重保护是防止误操作和队列放大效应的最低要求。

**实施约束**

- `reindex` 不应暴露为“无条件重建所有文档”的批量入口。
- 若文档尚未 `parse_status=success`，应拒绝 reindex 并返回明确诊断。

### 3.6 问答接口采用 `session + polling + 202 Accepted`

**结论**

- `POST /api/v1/answers` 创建 session，并采用 `202 Accepted`。
- 前端通过 `GET /api/v1/answers/{id}` 轮询状态，不在 `P0` 做同步长请求。
- `GET /api/v1/answers/{id}/retrieval` 在 `P0` 即保留正式资源，默认可供前端页面或内部调试读取。

**原因**

- 当前系统已经大量使用异步状态机与轮询，用户心智一致。
- `retrieving / synthesizing / answered / needs_scope / refused / failed` 只有持久化 session 才有稳定语义。
- retrieval trace 是排查 citation 和 refusal 的必要事实源，不应继续藏在日志里。

**实施约束**

- 轮询节奏属于实现细节，但状态枚举和 `202` 语义应在 freeze 前固定。
- `failed` 只保留给系统故障，不能吞并 `refused` 或 `needs_scope`。

---

## 4. 明确排除项

本次 tradeoff closure 同时明确排除以下方向进入 `P0`：

- 外部向量库
- 多模型路由
- vendor SDK 绑定式实现
- 同步长请求问答
- 只存 query 不存 snapshot 的 `search_result` scope
- 合并 `retry` 与 `reindex`
- 在生产主 schema 中引入 `evaluation_cases / evaluation_runs`

---

## 5. 进入实现 freeze 前必须完成的前置条件

1. 验证本地与 production PostgreSQL 的 `vector` 扩展可用性，并确定最终数据库基线镜像或扩展安装方式。
2. 冻结 provider 环境变量、secret 注入方式、timeout、重试和成本记录字段。
3. 冻结多队列配置结构，包括队列名、并发、重试和 worker 注册方式。
4. 冻结 `scope / citation / refusal / needs_scope / failed` 的 shared contract 文案和枚举。
5. 确认 `reindex` 的 UI 暴露条件与幂等保护策略。

这些前置条件是进入实现 freeze 的 gate，不再属于方向性 tradeoff。  
实际验证结果见 [Phase 2A Implementation Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md)，具体 runtime 命名与边界见 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)。

---

## 6. 对后续实现的约束

后续实现不得违背以下边界：

1. `documents` 仍是内容事实源，`chunk / citation / retrieval / answer session` 都是围绕它增量扩展。
2. `citation` 必须可回跳到 `document + chunk + locator`，不能让模型自由生成引用原文。
3. `search_result` scope 必须持久化 snapshot，不能在执行问答时重新临时搜索替代。
4. `refused`、`needs_scope`、`failed` 必须保持严格语义分离。
5. `P0` 评估集继续保留在 repo 资产中，不进入生产主 schema。

---

## 7. 下一步建议

在这些 tradeoff 已收敛后，下一阶段不应再反复讨论方向，而应进入：

1. 实现前 freeze 所需前置条件验证
2. `schema / shared-types / API contract / 状态机` 冻结
3. 再决定是否拆实现 lane
