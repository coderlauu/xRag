# xRag Phase 2B Architecture

**日期：** 2026-04-12
**版本：** `v5 / Phase 2B`
**状态：** draft
**适用范围：** `P0-01 ~ P1-01` 技术方案评估
**对应文档：**
- [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
- [Phase 2B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
- [v5 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
- [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- [Phase 2B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md)
- [Phase 2B API Design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md)
- [Phase 2B Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 1. 文档目的

这份文档用于回答 `Phase 2B` 的核心技术问题：

在 `Phase 2A` 已经完成可信问答闭环、且真实代码已经落地的前提下，如何增量引入：

- 更强的范围控制
- 更完整的 retrieval explain
- 更可读的 evidence package
- 最近问题与继续提问
- 后续可扩的 `ops` 增强

同时不破坏既有的：

- `citation`
- `refusal`
- `freshness`
- `release-readiness`

本文是技术方案评估稿，不代表已经进入 contract freeze 或实现 lane。

---

## 2. 本文评估边界

### 2.1 本次重点评估

- `P0-01` 扩展范围控制
- `P0-02` 检索实验台基线
- `P0-03` 证据包增强
- `P0-04` 问题历史与继续提问
- `P1-01` `ops` 面板增强的技术落点与排序

### 2.2 本次明确不做

- 直接进入实现 lane
- 重写 `Phase 2A` 的主问答链路
- 引入新的 conversation / memory assistant 架构
- 引入新的 explain pipeline
- 为 `ops` 单独建立第二套指标存储系统

---

## 3. 当前工程事实

基于当前 repo main 分支，`Phase 2B` 必须建立在以下已实现事实之上：

1. `Phase 2A` 已经不只是设计稿，而是现有生产工程基线。
   - `answers` 资源、`retrieval` trace、`document evidence`、`reindex`、`ops` 页面和 worker 流程都已存在。
2. 当前问答 contract 已固定以下枚举：
   - `AnswerScopeMode = global | search_result | document`
   - `AnswerSessionStatus = idle | retrieving | synthesizing | answered | needs_scope | refused | failed`
3. `search_result` 目前已经带有 snapshot 语义。
   - API 要求 `payload.document_ids` 与 `payload.truncated`。
4. Ask 页面当前仍是最小入口。
   - 还没有 typed scope filters。
   - 还没有 recent history 与 continue-asking 入口。
5. 当前 retrieval repo 的过滤能力本质上仍是 `documentIds`。
   - 这意味着 `标签 / 来源类型 / 时间范围` 不能只靠前端拼 payload，必须补齐 retrieval 层的正式过滤 contract。
6. 当前 retrieval trace 已经持久化 `lexical_score / semantic_score / final_score / used_in_answer / exclusion_reason`。
   - 但 `exclusion_reason` 仍是自由字符串，不是正式用户可见分类。
7. 当前 `answer_citations` 已经有内部 `claim_slot`，但外部 API 仍只暴露 flat citations。
   - 这说明 evidence grouping 有可复用基础，但还没有对外 contract。
8. 当前 `ops` API 与 `ops` 页面已经存在。
   - `Phase 2B` 的 `ops` 不是从零开始，而是增量增强。
9. production 侧继续具备：
   - `db.xrag.coderlau.cn` 的 pgweb 管理台
   - PostgreSQL `127.0.0.1:5432` 回环映射
   这意味着新增 schema 与 trace 字段仍有明确排查路径。

结论：

- `Phase 2B` 不需要重新发明一套问答系统。
- 重点是把现有 `Phase 2A` 的最小可信链路扩成“更可控、更可解释、更可回看”的正式产品 contract。

---

## 4. 技术评估准则

方案优先级不按“功能看起来多”排序，而按以下标准排序：

1. `信任边界`
   - 不能让 `citation / refusal / freshness` 退化。
2. `增量兼容`
   - 优先复用现有 `answers / retrieval_runs / answer_citations / ops`。
3. `单一事实链`
   - answer 页、history、retrieval workbench、detail 页必须共用同一条事实链。
4. `实现可冻结`
   - 能明确冻结到 `schema / shared-types / API / 状态机 / web state`。
5. `运维与排障成本`
   - 避免再造第二套 explain、metrics 或 session 系统。

---

## 5. 关键方案评估

### 5.1 扩展 scope 的 contract 应该怎么进来

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 新增 `tag / source_type / time_range` 等 `scope.mode` | 表面上直观 | enum 爆炸，容易把“选择方式”和“过滤条件”混为一体 | 不推荐 |
| 另起一套隐藏 filters，不进入正式 scope contract | 对现有 API 改动小 | history、retrieval explain 与 answer 页会出现两套事实 | 排除 |
| 保持 `scope.mode` 稳定，只扩展 typed `scope.payload` | 兼容现有 `global / document / search_result`，能保留 `search_result` snapshot 语义 | 需要对 `scope.payload` 与 retrieval 层一起升级 | 推荐 |

**推荐结论**

- `AnswerScopeMode` 保持不变：
  - `global`
  - `document`
  - `search_result`
- 新增一套 typed `ScopeFilterSet`，用于表达：
  - `tags`
  - `source_types`
  - `date_from / date_to`
- `search_result` 继续保留 `document_ids + truncated` 的 snapshot 语义。
- `global` 与 `search_result` 都允许携带同一套 `filters` 作为可见上下文。

**关键约束**

1. 不能为了支持 filters 去破坏现有 `search_result` snapshot。
2. 不能把 filters 只留在前端 URL state 或临时表单里。
3. retrieval 层必须正式理解 typed filters，而不是把所有过滤强行退化成手动 `document_ids`。

### 5.2 `继续提问` 的最小架构

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 引入 `conversation_threads / chat_messages` | 表达力最强 | 直接把产品带到多轮 memory assistant | 排除 |
| 原地修改旧 session | UI 看起来简单 | 破坏单次问答可核查性，历史与 citation 漂移 | 排除 |
| 新建 session，并用自关联记录来源 | 与现有状态机兼容，边界清晰，最容易 freeze | 需要新增 lineage 字段与 recent list API | 推荐 |

**推荐结论**

- `继续提问` 仍然创建新的 `answer_session`。
- 新 session 只允许显式继承：
  - 上一条 session 的 scope
  - 上一条 session 的 `session_id` 作为 lineage
- 不默认继承上一条答案文本作为隐藏 prompt context。

**关键约束**

1. 旧 session 保持只读。
2. `continue asking` 是 workflow convenience，不是 memory model。
3. `needs_scope / refused / failed` 的状态语义保持不变。

### 5.3 evidence package 应该如何增强

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 继续只返回 flat citations | 与现有 API 最兼容 | 无法支撑按 claim 分组与更清晰的 freshness 提示 | 不足 |
| 直接暴露模型推理轨迹 | 看起来解释更强 | 不稳定，也会越过产品边界 | 排除 |
| 在 flat citations 之上新增结构化 claim groups | 能兼容旧 contract，又能支撑新 UI | 需要新增 claim 级事实源 | 推荐 |

**推荐结论**

- `GET /answers/{id}` 保留现有 flat `citations`。
- 另外新增结构化 `evidence_groups` 响应字段。
- `evidence_groups` 的事实源应来自新的 claim 级数据，而不是临时解析 `answer_summary`。

**关键约束**

1. evidence grouping 不能脱离 citation。
2. 不能把 chain-of-thought 当作 evidence package 的一部分。
3. freshness 强提示应围绕 supporting evidence 做，而不是生成新的自由文本解释。

### 5.4 retrieval workbench 应该如何落地

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 为 explain 页面重新跑一套检索 | 页面更灵活 | 与真实答案断链，信任直接失效 | 排除 |
| 继续只暴露当前 flat retrieval items | 复用成本最低 | 无法完整支撑 contribution / exclusion / summary 视图 | 不足 |
| 复用 `retrieval_runs + retrieval_run_hits`，在原资源上扩充 summary 与 typed exclusion reason | 最符合单一事实链原则 | 需要收敛正式分类与响应 shape | 推荐 |

**推荐结论**

- retrieval workbench 继续以 `GET /answers/{id}/retrieval` 为 source-of-truth。
- URL state 留在 web 层，不写进 API contract。
- API 层新增：
  - retrieval summary 元信息
  - typed `exclusion_reason`
  - 更清晰的 lexical / semantic / rerank 贡献表达

### 5.5 `ops` 面板增强如何排序

**推荐结论**

- `ops` 增强继续保留在 `Phase 2B` 版本范围内，但排序维持 `P1-01`。
- `P0` 的 contract freeze 先只冻结“若 `ops` 进入实现，数据源只能来自现有基础表与 eval contract”。
- 不为 `ops` 提前阻塞 `scope / history / evidence / retrieval` 四条主链。

---

## 6. 推荐总体架构

```text
Browser
  -> Web SPA
      -> Ask Workspace
      -> Answer Detail
      -> Recent Answers
      -> Retrieval Workbench
      -> Ops
  -> API
      -> answers create/list/read/retrieval
      -> documents list/detail/evidence/reindex
      -> ops health/deployment/incidents/answer-summary
  -> PostgreSQL
      -> documents
      -> document_chunks
      -> answer_sessions
      -> answer_claims
      -> answer_citations
      -> retrieval_runs
      -> retrieval_run_hits
  -> Redis / BullMQ
      -> document-processing
      -> document-indexing
      -> answer-orchestration
  -> Worker
      -> current indexing / retrieval / synthesis pipeline
```

---

## 7. 推荐结论

1. `Phase 2B` 应被视为 `Phase 2A` 已实现主链的增量增强，不是重做。
2. `scope.mode` 保持稳定，新增 typed filters，而不是新增一组 mode 枚举。
3. `continue asking` 采用“新 session + 显式 lineage + scope-only 继承”。
4. evidence package 应以新增 claim 级事实源为基础，flat citations 继续保留兼容层。
5. retrieval workbench 必须复用现有 retrieval trace，不允许引入第二套 explain pipeline。
6. `ops` 增强可以继续做，但它不应阻塞 `P0-01 ~ P0-04` 的 contract freeze。
