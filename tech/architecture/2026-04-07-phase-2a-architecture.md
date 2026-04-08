# xRag Phase 2A Architecture

**日期：** 2026-04-07  
**版本：** `v4 / Phase 2A`  
**状态：** draft  
**适用范围：** `P0-01 ~ P0-07` 技术方案评估  
**对应文档：**
- [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
- [Phase 2A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md)
- [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [v4 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)

---

## 1. 文档目的

这份文档用于回答一个已经进入实现前的真实问题：

在 `Phase 1C` 已经完成统一导入、关键词搜索、时间线、诊断与运维基线的前提下，`Phase 2A` 的 `P0 可信问答闭环` 应该如何增量落地，才能同时满足：

- 对用户可信
- 对当前工程基线友好
- 对生产排障可观测
- 对后续 `P1` 可扩展

本文是技术方案评估稿，不代表已经进入编码或 contract freeze。

---

## 2. 本文评估边界

### 2.1 只覆盖 `P0`

- `P0-01` 问答入口
- `P0-02` 混合检索基线
- `P0-03` 最小范围控制
- `P0-04` 证据链
- `P0-05` 拒答与补救
- `P0-06` Freshness 可见性
- `P0-07` 评估门槛

### 2.2 不提前拉入

- `P1` 的扩展范围控制
- 检索实验台的完整 explain 视图
- 完整运维面板形态
- 问题历史、多轮对话、摘要、推荐、多模型路由

---

## 3. 当前工程事实

基于当前 repo 和 `Phase 1C` 实现，`Phase 2A` 必须承认以下事实：

1. 现有主链路已经是 `web -> api -> worker -> postgres/object storage/redis`。
2. 当前搜索依赖 `documents.search_text + search_vector(tsvector)` 的关键词基线，不存在向量检索层。
3. 当前 BullMQ 只维护一条 `document-processing` 队列，OCR、链接抓取和投影刷新都共享这条队列。
4. 当前 `documents`、`document_parse_jobs`、`document_processing_events` 已经是文档状态、任务状态和时间线的事实源。
5. 当前 API 已有 `documents list/detail/timeline/retry` 与 `ops` 读取面，可以承接增量资源而不需要重写前缀和命名。
6. production 已有 `db.xrag.coderlau.cn` 的 pgweb 管理台，以及 PostgreSQL `127.0.0.1:5432` 回环映射，意味着数据库内新增表、索引、诊断状态具备可排查路径。

结论：

- `Phase 2A` 不应再引入一套完全独立的内容系统。
- 所有新能力都应该围绕 `documents` 主事实源增量展开。

---

## 4. 技术评估准则

方案优劣不按“看起来先进”判断，而按以下标准排序：

1. `信任边界`
   没有 citation、refusal、freshness 可见性时，方案直接视为不合格。
2. `与现有系统一致`
   不能绕开当前 `documents / jobs / timeline / ops` 主链路另起炉灶。
3. `运维复杂度`
   单用户、私有知识库场景下，优先减少新增基础设施数量。
4. `延迟与可恢复性`
   用户问题需要在可接受时间内得到结论，同时失败后要可追踪、可重试。
5. `可演进`
   方案要允许后续增加 `P1` 的范围扩展、检索 explain 和更强证据展示，而不是把路堵死。

---

## 5. 关键方案评估

### 5.1 向量层选型

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 继续只用 PostgreSQL 全文检索 | 复用成本最低 | 无法支撑语义改写，不满足 `P0-02` | 排除 |
| PostgreSQL + `pgvector` | 与现有数据同库、事务一致性好、排障路径清晰、基础设施增量最小 | 需要启用扩展并固定首版 embedding 维度 | 推荐 |
| 外部向量库 | 检索能力更强、未来可扩容 | 新增同步链路、数据一致性与运维复杂度上升 | `P0` 不推荐 |
| 搜索引擎 + 向量混合引擎 | 理论能力最强 | 对当前单用户场景明显过重 | 排除 |

**推荐**

- `P0` 采用 `PostgreSQL + pgvector`。
- 向量数据与 chunk 元数据保持同库。
- 首版固定一个 embedding 模型与维度，不在 `P0` 做多模型维度兼容。

**理由**

- 当前 `Phase 1C` 的生产排查入口已经围绕 PostgreSQL 建立，继续同库最稳。
- `P0` 的核心问题是可信闭环，不是超大规模召回。

### 5.2 索引管线选型

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 上传后同步 chunk + embed | 实现直观 | 导入链路被 embedding 延迟绑死，freshness 和上传可靠性耦合 | 排除 |
| 继续塞进现有 `document-processing` 队列 | 复用当前 worker 最容易 | OCR/抓取 backlog 会直接拖慢问答 readiness 和在线问答 SLA | 不推荐 |
| 新增独立 `document-indexing` 队列 | 导入链路与问答索引解耦，利于 freshness 诊断 | 需要新增队列名和 worker 订阅 | 推荐 |

**推荐**

- 保留现有 `document-processing` 队列继续处理 `parse / ocr / fetch / projection`。
- 新增 `document-indexing` 队列负责 `chunk_document / embed_document`。
- 文档在 `parse_status=success` 后，再进入索引队列。

**理由**

- `P0-06` 需要让用户看懂“导入成功”和“进入问答索引 ready”是两件不同的事。
- 让 OCR、抓取和 embedding 共享一条队列，会直接削弱 freshness 预期。

### 5.3 在线问答执行模型

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| API 请求内同步完成 retrieval + generation | 交互最简单 | API 请求时长不可控，恢复和重试差，难展示 `retrieving / synthesizing` | 不推荐 |
| 复用现有 `document-processing` 队列跑问答 | 复用代码多 | 在线问答和重任务抢吞吐，SLA 不可控 | 排除 |
| 新增 `answer-orchestration` 队列，`POST /answers` 创建 session 后异步执行 | 与当前 polling/状态机模式一致，状态可恢复、可观测、可重试 | 需要新增 session 表和轮询接口 | 推荐 |

**推荐**

- 新增 `answer-orchestration` 队列。
- `POST /api/v1/answers` 负责创建 `answer_session` 并入队。
- `GET /api/v1/answers/{id}` 负责读取状态，前端轮询。

**理由**

- 当前产品已经广泛使用异步状态与轮询，这条路最符合既有工程心智。
- `retrieving / synthesizing / answered / refused / failed` 这些状态只有持久化 session 才有意义。
- 在线问答必须与 OCR、链接抓取隔离队列，否则评估门槛中的 `latency_p95` 无法守住。

### 5.4 Scope Contract

`P0` 只推荐三种 scope：

- `global`
- `search_result`
- `document`

**关键决策**

- `search_result` 不能只存“当时的搜索词”，否则后续重放会漂移。
- 推荐在 `scope_payload` 同时存：
  - 原始 query / filters
  - 当次解析得到的 `document_ids` 快照
  - 可选的来源页面信息

**理由**

- `P0-03` 的目标是“用户知道当前边界”，不是“让系统猜一个边界”。
- 如果 `search_result` 没有 snapshot，就无法稳定复现问答结果和引用来源。

### 5.5 Citation 生成策略

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 让模型自由输出引用文本 | 实现最快 | 模型可能虚构引用或漂移 quote | 排除 |
| 完全不用模型，直接拼 top hits | 最稳定 | 无法支撑跨文档综合回答 | 不满足目标 |
| 约束模型只能从 candidate chunk pack 中选择 chunk id，服务端重建 quote 和 locator | 兼顾可读答案与可验证引用 | 需要定义 chunk id / claim slot / validator | 推荐 |

**推荐**

- retrieval 阶段先产生固定 `candidate pack`。
- answer provider 只能引用 pack 内 chunk。
- 服务端根据 `chunk_id` 重建 `quote_text` 和 `locator`，而不是相信模型自由文本。
- validator 校验任何越界 chunk id、缺失 citation 或 claim 无支撑时，将结果打回 `refused` 或 `failed`。

### 5.6 Refusal / Needs Scope / Failed 的边界

这是 `P0` 的核心 contract，必须固定：

- `needs_scope`
  当前 evidence 存在，但范围过大或候选冲突明显，缩小范围后有合理概率得到稳定答案。
- `refused`
  即使在当前 scope 下，也没有足够 ready evidence 支撑结论，或 citation validator 无法为关键 claim 绑定证据。
- `failed`
  provider 超时、队列异常、系统错误等基础设施问题。

**结论**

- `needs_scope` 是产品指引，不是错误。
- `refused` 是可信系统的正常输出，不是失败兜底。
- `failed` 只保留给基础设施和系统故障。

### 5.7 评估数据的存放方式

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 一开始就把 `evaluation_cases / evaluation_runs` 落到生产数据库 | 查询方便 | 提前扩大主数据模型，评估资产与产品运行态耦合 | `P0` 不推荐 |
| 先把 golden set 维护在 repo 内，用脚本或测试夹具执行 | 与版本控制一致、回放稳定、成本最低 | 线上趋势查询不如落库方便 | 推荐 |

**推荐**

- `P0` 先把 golden set 作为 repo 内版本化资产维护。
- 线上只暴露聚合指标，不把评估集管理做进主数据库。
- 如果后续 `P1-03` 需要趋势面板，再评估是否新增评估结果表。

---

## 6. 推荐总体架构

```text
Browser
  -> Web SPA
      -> Ask Workspace
      -> Search (keyword baseline + search_result scope source)
      -> Detail (evidence view)
      -> Ops
  -> API
      -> documents list/detail/timeline/reindex
      -> answers create/read/retrieval
      -> ops answer summary
  -> PostgreSQL
      -> documents
      -> document_chunks
      -> answer_sessions
      -> retrieval_runs / retrieval_run_hits
      -> answer_citations
      -> existing jobs / timeline tables
  -> Redis / BullMQ
      -> document-processing
      -> document-indexing
      -> answer-orchestration
  -> Worker
      -> existing parse/ocr/fetch/projection handlers
      -> chunk / embed handlers
      -> answer orchestration handler
  -> Embedding Provider
  -> Answer Provider
```

---

## 7. 明确推荐

1. `P0` 采用 `PostgreSQL + pgvector`，不引入外部向量库。
2. 新增两条队列：`document-indexing`、`answer-orchestration`，不要把问答和 OCR 共用当前队列。
3. 问答采用 `session + polling` 模型，而不是 API 同步长请求。
4. `search_result` scope 必须保存 document snapshot，而不是只保存搜索词。
5. citation 必须是结构化的 `chunk_id -> quote_text / locator`，不允许模型自由造引用。
6. golden set 与评估门槛先留在 repo 资产，不在 `P0` 做成数据库产品能力。

这些推荐中，最关键的方向性取舍已经在 [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md) 中正式收敛。实现前的 runtime 命名与部署边界以下面的 [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md) 为准；真正进入编码时，以 [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md) 作为主线程冻结基线。

---

## 8. 主要风险与缓解

### 8.1 `pgvector` 扩展可用性

- 风险：production PostgreSQL 尚未确认启用 `pgvector`
- 缓解：在进入编码前先做一次数据库扩展可用性检查；若不可用，才重新评估外部向量层

### 8.2 队列过多导致复杂度上升

- 风险：新增两条队列会增加 worker 配置
- 缓解：仍使用同一套 Redis/BullMQ 基础设施，只增加 queue name 与 worker 订阅，不引入新中间件

### 8.3 embedding 模型维度被未来策略锁死

- 风险：`pgvector` 列维度需要固定
- 缓解：`P0` 明确只支持单一 embedding 模型；多模型路由已列入 deferred

### 8.4 `search_result` snapshot 过大

- 风险：把大量 document id 写入 scope payload 可能膨胀
- 缓解：`P0` 限制 snapshot 规模，必要时只保留 top N eligible documents

### 8.5 在线问答延迟

- 风险：异步问答如果没有独立队列和并发策略，`latency_p95` 可能失控
- 缓解：`answer-orchestration` 单独限流与并发；门槛由评估计划约束

---

## 9. 已收敛的关键 tradeoff

以下事项已经在 [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md) 中固定：

1. 向量层采用 `PostgreSQL + pgvector`
2. worker 拓扑采用“单服务、多队列”
3. provider 采用内部抽象 + OpenAI-compatible HTTP
4. `search_result` scope 采用上限为 `100` 的 snapshot
5. `reindex` 作为独立动作保留，并最小开放给用户
6. 问答接口采用 `session + polling + 202 Accepted`

---

## 10. 进入实现 freeze 前的前置条件

1. 验证本地与 production PostgreSQL 的 `vector` 扩展可用性
2. 冻结 provider env、secret 注入、timeout 和成本记录 contract
3. 冻结多队列配置、并发和重试策略
4. 冻结 `citation / scope / refusal` 的 shared contract
