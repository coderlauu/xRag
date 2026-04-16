# Phase 2C Architecture

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** draft
**对应文档：**
- [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
- [Phase 2C P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 2C` 进入 contract freeze 前最关键的架构问题：

- 治理主板是否继续由前端拼接现有四个 `/ops/*` 接口
- runtime 指标、evaluation 指标、incident、deployment 应如何组成同一条事实链
- 哪些治理结论应该在 API 层形成正式 read model，哪些继续留在页面层

本阶段不定义最终 DTO 细节，只冻结架构方向和边界。

---

## 2. 当前工程事实

1. 当前 `ops` 页面依赖四个现有只读接口：
   - `GET /api/v1/ops/health-summary`
   - `GET /api/v1/ops/incidents`
   - `GET /api/v1/ops/answer-summary`
   - `GET /api/v1/ops/deployments/latest`
2. 当前页面里仍有大量客户端拼装逻辑：
   - readiness rate
   - readiness status 文案
   - incident 分组
   - recommended actions
   - degradation / rollback 建议
3. 当前 `answer-summary` 同时混合了 readiness 和 runtime quality，但只覆盖：
   - `embedding_backlog`
   - `ready_document_count`
   - `stale_document_count`
   - `failed_document_count`
   - `answer_latency_p95`
   - `citation_coverage`
   - `refusal_rate`
   - `avg_token_cost_usd`
4. 当前并没有现成的运行时事实源可以直接给出：
   - `groundedness`
   - `refusal_precision`
   - `Recall@K`
   - `MRR`
   - `hit_in_answer_rate`
5. 当前 `incidents` 接口主要来自 `document_parse_jobs + uploads` 聚合，`IncidentSource` 虽预留 `deploy / ci`，但现阶段没有稳定的持久事实源。
6. 当前 `deployments/latest` 只读环境变量，不具备历史发布序列，也无法支撑趋势关联。
7. `Phase 2A` 已明确：golden set 与评估门槛最初保留在 repo 资产，不在 `P0` 进入数据库产品能力；但 `Phase 2C` 的版本目标已经从“能评估”转向“要把治理面板做成正式产品”。

---

## 3. 架构问题与结论

### 3.1 是否继续由前端拼接四个旧接口

**备选**

| 方案 | 优点 | 风险 | 结论 |
| --- | --- | --- | --- |
| 继续由前端拼装现有四个接口 | 复用成本最低 | 口径、时间窗口、推荐动作都停留在前端 heuristics；多端难以共享 | 不足 |
| 把更多字段继续塞进 `GET /ops/answer-summary` | 保持旧路径 | 会把 summary 变成过载接口，语义继续混乱 | 不推荐 |
| 新增治理 read model，保留旧接口兼容 | API 形成正式聚合口径，页面不再自己造判断 | 需要新增 DTO 和 read model 组装层 | 推荐 |

**推荐结论**

- `Phase 2C` 应新增面向治理主板的 read model 资源，而不是继续让前端自己拼装治理结论。
- 现有 `health-summary / incidents / answer-summary / deployments/latest` 保留兼容和 drill-down 价值，但不再作为主板唯一事实来源。

### 3.2 runtime 指标和 evaluation 指标是否共用同一层

**结论**

- `Phase 2C` 采用“双层质量事实”：
  - `runtime quality`
    - 来自线上会话与文档现状
    - 包括 `citation_coverage / latency / avg_token_cost_usd / refusal_rate / backlog / freshness_lag`
  - `evaluation quality`
    - 来自离线或准离线评估运行
    - 包括 `Recall@K / MRR / hit_in_answer_rate / groundedness / refusal_precision`

**原因**

- 当前线上事实源没有能力直接产出 `groundedness` 和 `refusal_precision`。
- 若把 `refusal_rate` 冒充 `refusal_precision`，治理面板会在语义上失真。

**实施约束**

- `runtime quality` 与 `evaluation quality` 必须在 API 和 shared types 中分区表达。
- 同名指标只能在语义一致时共享字段名；否则必须分开。

### 3.3 治理面板是否需要新的持久事实

**结论**

- `Phase 2C` 不建议引入泛化的 `ops_metrics_daily` 或 `ops_dashboard_snapshots`。
- 但建议新增两类最小持久事实：
  - `evaluation_runs`
  - `deployment_records`

**原因**

- readiness、incident 和 runtime latency / cost 等指标仍可从现有 OLTP 表按时间窗口聚合，不需要先做第二套 metrics store。
- 但 `groundedness / refusal_precision` 与历史部署序列无法从当前 live 表稳定恢复，必须有正式持久来源。

### 3.4 incident 聚类是否需要单独落库

**结论**

- `Phase 2C / P0` 不建议先新增通用 `ops_incidents` 表。
- incident 聚类首版继续采用“在 API 层基于稳定字段实时聚合”的方式：
  - runtime incident
    - 来自 `document_parse_jobs + uploads`
  - deploy incident
    - 来自 `deployment_records` 派生
  - `ci`
    - 当前保留枚举位，但不要求在 `P0` 形成完整历史链

**原因**

- 当前版本的核心目标是“先把判断和处置优先级做对”，不是先建立统一事件仓库。
- 过早抽象为通用 incident 表会显著扩大 ingestion 面。

### 3.5 recommended actions 放在前端还是后端

**结论**

- `Phase 2C` 应由 API 返回结构化推荐动作和风险摘要，Web 负责展示文案。

**原因**

- 当前推荐动作完全由前端拼接，无法保证不同入口的口径一致。
- 后续如果 `Ask / Search / Detail` 需要暴露轻量 notice，应复用同一套 action codes 和 risk summary。

**实施约束**

- 推荐动作返回结构化 `code / priority / summary / related_surface`，而不是只返回长文案。
- Web 可以本地映射展示文案，但不能自己发明新的动作判断。

### 3.6 是否把治理状态扩散到 `Ask / Search / Detail`

**结论**

- `Phase 2C / P0` 只允许轻量 notice 扩散到终端页面，不重新定义终端状态机。
- 主治理判断仍停留在 `ops` 主板。

**原因**

- 当前版本是内部治理版本，不应把终端页面重新做成运维入口。
- 若把治理状态直接改写成问答状态，会破坏 `Phase 2A / 2B` 已冻结的 trust model。

---

## 4. 推荐总体架构

```text
Browser
  -> Web SPA
      -> Ask / Search / Detail
          -> optional governance notice
      -> Ops Board
          -> reads ops overview + ops trends
  -> API
      -> existing ops resources
          -> health-summary
          -> incidents
          -> answer-summary
          -> deployments/latest
      -> new ops read models
          -> overview
          -> trends
  -> PostgreSQL
      -> documents
      -> answer_sessions
      -> answer_citations
      -> document_parse_jobs
      -> uploads
      -> evaluation_runs
      -> deployment_records
  -> Worker / CI
      -> existing indexing and answer orchestration
      -> evaluation runner writes evaluation_runs
      -> deploy evidence writer writes deployment_records
```

---

## 5. 推荐架构边界

### 5.1 API 读模型边界

- `overview`
  - 返回当前治理主板所需的摘要块
- `trends`
  - 返回固定时间窗口下的时间序列
- 旧接口保留：
  - 兼容旧页面
  - 作为 drill-down 或细节读取面

### 5.2 数据边界

- 仍从主业务表聚合：
  - readiness
  - runtime quality
  - runtime incident
- 新增持久事实：
  - evaluation runs
  - deployment records

### 5.3 计算边界

- API 负责：
  - 统一时间窗口
  - action code 生成
  - risk summary
  - clusters
- Web 负责：
  - 展示布局
  - 轻量文案映射
  - 筛选 / tab / chart 呈现

---

## 6. Freeze-Ready 结论

1. `Phase 2C` 已经不适合继续沿用“前端拼四个旧接口”的临时架构。
2. `runtime quality` 与 `evaluation quality` 必须分区表达，不能再在文档或接口里混用。
3. `evaluation_runs + deployment_records` 已经成为 `Phase 2C` 的合理最小新增持久事实。
4. `incident clustering` 和 `recommended actions` 可以在 `P0` 通过 API read model 实现，不必先建通用 incident 仓库。
5. 在这些结论下，没有新的硬 blocker，可以进入 `contract-freeze`。
