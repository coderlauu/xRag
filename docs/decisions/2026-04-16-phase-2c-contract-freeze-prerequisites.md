# Phase 2C Contract Freeze Prerequisites

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** draft
**对应文档：**
- [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
- [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
- [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)

---

## 1. 目的

这份文档只回答一个问题：

- `Phase 2C` 现在是否可以进入 contract freeze

结论先写：

- 可以进入
- 当前没有新的硬 blocker
- 但 contract freeze 必须把下面这些前置项一次性锁死

---

## 2. 已完成的前提

以下方向已经在技术评估中收敛：

1. `Phase 2C` 继续以内部治理主板为核心，不重写 `Ask / Search / Detail` 主状态机。
2. 质量面正式分成：
   - `runtime quality`
   - `evaluation quality`
3. `evaluation_runs + deployment_records` 已成为合理的最小新增持久事实。
4. `overview + trends` 已收敛为新的主 read model 方向。
5. `ops` 当前不引入自动 remediation、自动回滚和评测管理平台。

---

## 3. Contract Freeze 必须锁定的事项

### 3.1 资源与路径

- 是否正式新增：
  - `GET /api/v1/ops/overview`
  - `GET /api/v1/ops/trends`
- 现有路径的角色边界：
  - `answer-summary`
  - `incidents`
  - `deployments/latest`
  - `health-summary`

### 3.2 新 shared types

- `OpsOverviewResponse`
- `OpsReadinessSnapshot`
- `OpsRuntimeQualitySummary`
- `OpsEvaluationQualitySummary`
- `OpsIncidentCluster`
- `OpsReleaseGuard`
- `OpsRecommendedAction`
- `OpsTrendsResponse`

### 3.3 最小新增枚举 / 分类

至少需要固定：

- `readiness_blocking_reason`
- `recommended_action_code`
- `release_guard_risk_level`
- `trend_window`
- `trend_source`

注意：

- `IncidentSource` 现有枚举继续保留，不在 freeze 里重命名
- `ci` 可以暂时无数据，但不能在 freeze 时删除枚举位

### 3.4 数据新增对象

- `evaluation_runs`
- `deployment_records`

同时要明确：

- 不新增 `ops_metrics_daily`
- 不新增 `incident_clusters`
- 不新增 `evals` 管理表

### 3.5 指标语义边界

contract freeze 时必须明确：

1. `refusal_rate != refusal_precision`
2. `groundedness` 只来自 evaluation facts
3. `citation_coverage` 需要标明来源：
   - runtime
   - evaluation
4. readiness 指标与 Ask 的真实 retrieval 阻断条件如何对齐

### 3.6 轻量 notice 边界

如果 `Ask / Search / Detail` 需要轻量治理提示，freeze 时必须明确：

- 只返回轻量 notice
- 不改写既有 answer status
- 不把 `ops` 风险直接变成终端状态机字段

---

## 4. 当前仍需在 Freeze 中写清的点

这些不是 blocker，但必须在 freeze 文档里落成正式决定：

1. `overview` 是否直接包含 `recommended_actions`
2. `trends` 的固定时间窗口是否只允许 `24h / 7d / 30d`
3. `evaluation_runs` 的指标采用显式列还是少量 JSON 补充字段
4. `deploy incident` 在 `P0` 是来自 `deployment_records` 派生，还是允许继续缺席
5. `Ask / Search / Detail` 的轻量 notice 是否进入 `P0` 还是继续 freeze-late

---

## 5. 推荐结论

建议下一步直接进入 `contract-freeze`，并按以下顺序推进：

1. 冻结 `/ops/overview + /ops/trends` 的资源边界
2. 冻结 `evaluation_runs + deployment_records` 的最小数据面
3. 冻结 shared types、枚举和时间窗口
4. 最后再决定哪些轻量 notice 需要暴露到 `Ask / Search / Detail`

在这个顺序下，`Phase 2C` 可以保持边界清晰，不会重新膨胀成 observability 平台。
