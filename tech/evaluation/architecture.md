# 技术方案：RAG 评测体系

- `status`: Step C review
- `date`: 2026-08-13
- `prd`: [Phase 4 PRD](../../docs/prd/2026-08-13-rag-evaluation-prd.md)
- `data_model`: [数据模型](data-model.md)
- `api`: [API 契约](api.md)

## 1. 架构结论

第一版不建评测微服务或页面。Spring Boot 内新增 `com.app.evaluation`，由三个深模块承担外部接口：

```text
EvaluationDatasets ── publish/retire ──> 不可变 dataset revision
EvaluationRuns     ── start/cancel ───> 同源录制 + 运行 manifest
EvaluationReports  ── score/render/diff/review ──> 分层结果与 artifact
```

调用者不接触 RAGAS 类、SQL、阶段表或报告模板。确定性评分在进程内；语义评分只穿过
`SemanticEvaluator` seam。生产可接外部 RAGAS worker，测试使用确定性 fake；PoC 不通过时可换成 Java
Judge adapter，不改变领域或 HTTP 接口。

## 2. 包与模块

```text
com.app.evaluation
├── dataset      # 草稿校验、canonical hash、不可变 revision、split/资格
├── run          # 预算、manifest、样本调度、取消、恢复、同源录制
├── metric       # 资格、确定性指标、语义 evaluator seam、聚合
├── report       # manifest/JSONL/CSV/Markdown/failures/diff
├── review       # 人工复核追加与最终采用值
├── retention    # 正文快照到期清理，身份/聚合/审计保留
└── web          # 受控 HTTP；CLI 只是 HTTP client/脚本
```

模块接口保持小：

```java
interface EvaluationDatasets {
  DatasetRevision publish(DatasetDraft draft);
  void retire(DatasetRevisionId id);
}

interface EvaluationRuns {
  EvaluationRunId start(StartEvaluation command);
  void cancel(EvaluationRunId id);
}

interface EvaluationReports {
  EvaluationReport score(EvaluationRunId id, ScorePolicy policy);
  EvaluationDiff compare(EvaluationRunId before, EvaluationRunId after);
  EvaluationReport applyReview(ManualReview review);
}
```

查询使用只读 query objects，不把每张表做成 CRUD module。

## 3. 同源录制 seam

`QaEvaluationExecution` 是评测对问答模块唯一的新 seam：输入样本和已冻结的运行约束，返回 `turnId`；
它复用正常提交、排队、检索和生成链路，不复制 Pipeline。`TurnRecordingAssembler` 在 turn 终态后一次性从
已有 Phase 3 事实表装配：子问题、意图 revision/匹配、动作、检索 source/target/funnel、工具调用、实际
Prompt 证据顺序、assistant message、generation task、stage 和 AI logical call。

禁止：第二次改写、第二次检索、评测专用 Rerank、从“当前意图树”重算旧 turn。若实际模型消费的证据
无法从现有事实重建，该样本标 `MISSING_EVIDENCE`，不以近似结果代替。

每个评测样本使用独立 conversation，避免样本间记忆污染；多轮能力由未来显式 multi-turn sample 表达，
第一版不复用前一条样本会话。

## 4. 运行状态机与恢复

```text
CREATED -> VALIDATING -> RECORDING -> RECORDED -> SCORING -> REPORTED
              |             |            |           |
            FAILED        PARTIAL      PARTIAL     PARTIAL
              \------------- CANCELED / BUDGET_EXHAUSTED
```

- 单个样本：`PENDING/RUNNING/RECORDED/SCORED/FAILED/CANCELED/BUDGET_EXHAUSTED`。
- claim 通过数据库 CAS；同一 run 同时只有一个协调器。应用重启把遗留 `RUNNING` 样本恢复为 `PENDING`，
  但已有 `turn_id` 的样本先检查终态并装配，不重复提交。
- 预算是四维共享账本：样本数、Provider 请求/Token、金额、wall deadline。每次 outbound 前原子保留预算，
  实际 usage 回填；无法计价不是零费用，而是 `COST_UNKNOWN` 并阻止要求金额硬上限的模式继续。
- `DETERMINISTIC_CI` 只能绑定测试 profile 和 fake Provider；presence of key 不改变模式。

## 5. 身份与 canonicalization

- sample revision hash：canonical JSON（字段顺序、Unicode NFC、LF、稳定数组语义）SHA-256。
- dataset revision hash：metadata + 有序 member `(sampleRevisionId, split, weight, ordinal)`。
- run manifest hash：dataset + fixture + code/build + QA config/Prompt + actual model constraints + metric/Judge
  manifest + policy/pricing。
- Hash 是内容校验，不是授权令牌；数据库仍使用 UUID 主键。
- Prompt、metric 或 Judge 变化产生新 revision；不把新旧分数接到同一 baseline。

## 6. 指标管线

`MetricEngine` 对每个 `(recording, metricRevision)` 先执行资格判断，结果闭集：

```text
VALUE | NOT_APPLICABLE | MISSING_EVIDENCE | EVALUATION_FAILED
```

确定性 adapters：意图/动作、Doc/Chunk Hit@K、Recall@K、MRR、行为、终态、阶段时延、usage/cost。它们是
进程内实现，不为每个指标暴露独立外部接口。

语义 seam：

```java
interface SemanticEvaluator {
  SemanticBatchResult evaluate(SemanticBatch batch, EvaluationBudget budget);
}
```

只有两个 adapter 才使 seam 成立：`RagasWorkerAdapter`（候选生产实现）与 `FakeSemanticEvaluator`（测试）。
worker 只接收已通过授权检查的最小 payload，返回逐样本逐 repetition verdict、理由、usage 和实际模型；
不得回写 run 状态或直接访问生产数据库。

## 7. RAGAS PoC 闸门

Step D 的第一批实现不直接引入 RAGAS。先固定版本和依赖锁，对 12 条中文双人校准集离线运行：

1. 验证 faithfulness、answer relevance、reference correctness 的输入/输出、NaN 与中文适配；
2. 保存默认/适配 Prompt、实际 Judge/Embedding、逐轮结果和费用；
3. 按指标统计人机一致、误判类型、不可评率和费用；
4. 由项目所有者选择：接入 RAGAS adapter、改用自有 Judge adapter，或首版只交付确定性指标。

PoC 是 Step D 前置工单和显式技术裁决，不允许 adapter 选型悄悄发生在实现中。

## 8. 报告与人工复核

- PostgreSQL 是运行身份、逐样本观测、指标、review 和 baseline 的事实源。
- JSON/JSONL/CSV/Markdown 按需从事实源渲染；生成失败可重跑，不把 artifact 当唯一真相。
- 报告保存 hash 与生成器 revision；正文文件放受控临时目录/下载响应，不进 Git。
- review 是 append-only；`AUTO`、`MANUAL`、`ADOPTED` 三值并存。一次 review 只能引用已有 observation，
  更正标签则创建新 sample revision，不在 review 中改 gold。
- diff 先比较 manifest。身份不一致返回 `NOT_STRICTLY_COMPARABLE` 及差异列表；仍可展示事实但不判胜负。

## 9. 数据安全与保留

- 只允许 `FIXTURE_AUTHORIZED` 样本进入外部 Judge；其余样本在资格层跳过。
- 原始 Prompt、API Key、Provider 错误体、思维链不进入 evaluation 表。
- recording 正文单独保存并标 `expires_at`；最近 20 个完整 run 或 30 天先到者物理清理。run manifest、
  聚合、metric observation 和 review 审计继续保留。
- 物理清理是“有时限派生评测快照”的明确例外，不适用于 dataset/sample 业务定义；后者逻辑废弃。

## 10. 并发、事务与失败

- Provider/Judge 调用与报告文件 IO 全在数据库事务外；事务只做 claim、短状态转换和批量结果写入。
- 默认 sample concurrency=1，显式模式最多 2；性能 baseline 固定为 1，避免把并发排队混入比较。
- 单样本失败不回滚已完成样本。解析失败、Judge 失败和业务低分使用不同状态。
- 取消先阻止新工作，再传播到当前 QA/Judge 调用；最终报告可生成部分结果。

## 11. 可观测性与测试 seam

- run/sample 携带 turn/trace/logical-call ID；evaluation 不复制 AI diagnostic 事件。
- 使用 monotonic duration，wall clock 只记录发生时间。
- 深模块接口是主要测试面；PostgreSQL 用 Testcontainers，本地文件/renderer 用临时目录，QA 与 Judge 使用
  fake adapter。不得通过 mock 每个 repository 来验证编排。
- 真实 Provider/Judge 测试需要 `EVALUATION_REAL_ENABLED=true`、固定 dataset revision 和全部预算上限。

## 12. 不采用的方案

| 方案 | 原因 |
|---|---|
| 第二个 `/rag/eval` 请求 | 证据与答案不同源，且旁路会漂移 |
| Python 拥有 run/数据集数据库 | 身份与 Phase 3 事实跨系统双写，恢复复杂 |
| 每个 metric 一个公共 interface | 浅 module，调用者被迫理解内部编排 |
| 报告文件作为事实源 | 无法可靠 review、重算或审计 |
| RAGAS 类型进入 Schema/API | breaking change 会扩散到领域与调用者 |
