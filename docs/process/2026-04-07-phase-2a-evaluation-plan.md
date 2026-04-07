# Phase 2A Retrieval And Answer Evaluation Plan

**日期：** 2026-04-07  
**状态：** draft  
**适用范围：** `v4 / Phase 2A` 的混合检索、引用、拒答、延迟与成本评估

---

## 1. 目的

这份文档定义 `Phase 2A` 的最小评估基线，避免后续出现：

- “看起来更聪明”，但没有证据支撑
- 召回变多了，但答案反而更不可信
- 延迟、token 成本和拒答率无从比较

本计划要求在进入正式实现前，先把可重复评估的方法定下来。

---

## 2. 评估原则

1. 先评 retrieval，再评 answer，不把所有问题都归咎给模型
2. 引用是否成立比语言是否流畅更重要
3. 正确拒答是成功结果，不是失败
4. 线上指标只做补充，离线 golden set 才是版本比较基线

---

## 3. 用例分层

### 3.1 Single Fact

单篇文档内即可验证的事实问答。

示例：

- “违约金比例是多少？”
- “路线图里五月优先投入什么？”

### 3.2 Cross Document

需要综合多篇文档才能给出答案。

示例：

- “当前路线图和复盘都认为最优先的能力是什么？”

### 3.3 Scope Bound

问题只有在限定范围后才合理。

示例：

- “在链接来源里，哪些资料提到高级过滤器？”

### 3.4 Refusal

知识库中证据不足，应明确拒答。

示例：

- “团队版会在几月上线？”

### 3.5 Freshness

新导入资料是否在 freshness 窗口内进入可问答状态。

---

## 4. 指标定义

### 4.1 Retrieval

- `Recall@K`
- `MRR`
- `hit_in_answer_rate`

### 4.2 Answer

- `groundedness`
  - 0.0 到 1.0
  - 看答案中的核心 claim 是否都能被引用支持
- `citation_coverage`
  - 核心 claim 中有 citation 的比例
- `refusal_precision`
  - 应拒答的样例里，系统是否真的拒答

### 4.3 Runtime

- `latency_p50`
- `latency_p95`
- `avg_token_cost_usd`
- `embedding_backlog`
- `freshness_lag_p95`

---

## 5. Golden Set 最小要求

`Phase 2A` 第一版至少维护：

- `10` 条 single fact
- `8` 条 cross document
- `6` 条 scope bound
- `8` 条 refusal
- `5` 条 freshness

总数建议不少于 `37` 条。

每条用例至少包含：

- 问题
- 固定 scope
- 期望命中文档
- 期望行为：`answer / needs_scope / refuse`
- 简短人工判分说明

---

## 6. 评估流程

### 6.1 离线评估

每次主链路策略变更时，先跑：

1. retrieval baseline
2. hybrid retrieval
3. answer generation
4. citation validation
5. refusal validation

### 6.2 灰度评估

上线前在 feature flag 下进行：

- 内部样本回放
- 小流量 canary
- 记录 latency / cost / citation coverage

### 6.3 线上抽样

上线后抽样真实问答会话，观察：

- refusal 是否过高
- retrieval no hits 是否集中在某类资料
- citation missing 是否集中在某类 chunk

---

## 7. 版本门槛建议

`Phase 2A` 进入实现或灰度前，建议至少达到：

- `Recall@10 >= 0.80`
- `groundedness >= 0.85`
- `citation_coverage >= 0.95`
- `refusal_precision >= 0.90`
- `latency_p95 <= 8000ms`
- `avg_token_cost_usd` 在可接受预算内

若任一指标明显不达标，优先降 scope，而不是继续扩功能。

---

## 8. 回滚与降级判据

出现以下情况时，应优先降级：

- `groundedness` 或 `citation_coverage` 明显下滑
- `provider_timeout` 持续高于阈值
- `freshness_lag_p95` 超过设定窗口
- `avg_token_cost_usd` 连续超预算

降级顺序建议：

1. 关闭 answer generation，只保留 semantic retrieval
2. 关闭 semantic retrieval，只保留 keyword baseline
3. 暂停新索引写入，保留现有可用索引

---

## 9. 后续资产建议

后续可补：

- `docs/generated/` 下的 golden set 快照
- 评估脚本输入输出格式
- groundedness 人工评审 rubric
- 成本预算按 provider / model 维度拆分
