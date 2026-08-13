# 数据模型：RAG 评测体系

- `status`: Step C review
- `date`: 2026-08-13
- `architecture`: [架构](architecture.md)
- `prd`: [PRD](../../docs/prd/2026-08-13-rag-evaluation-prd.md)

## 1. 状态所有权

| 状态 | 唯一来源 | 删除语义 |
|---|---|---|
| dataset/sample 定义与不可变 revision | PostgreSQL | 逻辑废弃；历史 revision 不改 |
| fixture manifest | 版本控制 artifact + DB hash 快照 | 新 revision 替代 |
| run/sample/recording/metric/review | PostgreSQL | manifest/审计保留；到期正文物理清理 |
| 报告文件 | 可重建 artifact | 到期物理删除 |
| Judge Prompt/依赖锁 | 版本控制 artifact + run hash | 不由运行时修改 |

## 2. 表清单

| 表 | 作用 |
|---|---|
| `eval_sample_revision` | 不可变问题、gold、reference/rubric 与 provenance |
| `eval_dataset_revision` | 不可变数据集 manifest |
| `eval_dataset_member` | revision 的有序成员、split、weight |
| `eval_run` | run manifest、模式、预算、状态和聚合计数 |
| `eval_run_sample` | 每样本 claim、turn 关联、状态与用量 |
| `eval_recording` | 同源录制的结构化快照与有时限正文 |
| `eval_metric_observation` | 逐样本、逐 metric、逐 repetition 原始结果 |
| `eval_manual_review` | append-only 人工复核 |
| `eval_baseline` | dataset/fixture 下当前 baseline 指针 |

## 3. 关键字段与约束

### 3.1 `eval_sample_revision`

```sql
id uuid primary key,
sample_key varchar(96) not null,
revision_no integer not null,
content_hash char(64) not null unique,
input_text text not null,
scenario varchar(64) not null,
risk_level varchar(16) not null,
requires_rag boolean not null,
expected_intents jsonb not null,
expected_actions jsonb not null,
must_evidence_ids jsonb not null,
nice_evidence_ids jsonb not null,
reference_answer text,
rubric jsonb,
provenance varchar(32) not null,
external_judge_allowed boolean not null,
created_at timestamptz not null,
retired_at timestamptz,
unique(sample_key, revision_no)
```

JSON 列必须为 array/object 且 canonicalizer 能稳定重算 hash。`reference_answer` 与 `rubric` 至少一个存在的
要求由适用指标校验，不对 SYSTEM 样本强加错误约束。retire 不改变 content hash。

### 3.2 dataset

`eval_dataset_revision(id, dataset_key, revision_no, manifest_hash, description, status,
created_at, retired_at)`；状态 `PUBLISHED/RETIRED`。`eval_dataset_member(dataset_revision_id,
ordinal, sample_revision_id, split, weight)`，主键 `(dataset_revision_id, ordinal)`，同一 sample 在同一
revision 唯一，split 闭集 `CALIBRATION/DEVELOPMENT/MAIN/RELEASE_HOLDOUT`。

### 3.3 run

`eval_run` 保存：`id`、dataset/fixture identity、`mode`、`manifest_json/hash`、code/build、Prompt/config、
SUT/Judge/metric/pricing identity、四类预算及 consumed、状态、cancel flag、计数、时间和 failure code。

不变量：

- mode 为 `DETERMINISTIC_CI/REAL_BASELINE/JUDGED_RELEASE`；
- 真实模式四类预算均非空且大于零；CI mode 的 `real_provider_allowed=false`；
- consumed 非负且不能在终态后继续增加；
- status 闭集与架构状态机一致；`completed_at` 当且仅当终态非空；
- `manifest_hash` 唯一校验内容，不禁止相同 manifest 多次运行。

### 3.4 run sample 与 recording

`eval_run_sample(run_id, ordinal, sample_revision_id, status, attempt_no, turn_id, conversation_id,
failure_code, usage_json, started_at, completed_at)`，主键 `(run_id, ordinal)`，`turn_id` 非空后唯一。

`eval_recording(run_id, ordinal, schema_version, facts_json, input_text, response_text,
contexts_json, created_at, expires_at, content_purged_at)`：

- `facts_json` 保存 ID、顺序、终态、阶段、实际模型、降级和时间，不保存思维链；
- 正文三列仅授权夹具可写；清理后置 null 并写 `content_purged_at`，facts 与 hash 保留；
- recording 成功后不可 update，唯一允许的更新是一次正文清理。

### 3.5 metric 与 review

`eval_metric_observation` 的唯一键 `(run_id, sample_ordinal, metric_key, metric_revision,
repetition_index)`；保存 `status`、numeric/text verdict、reason、evaluator manifest、usage/cost、time。
status 为 `VALUE/NOT_APPLICABLE/MISSING_EVIDENCE/EVALUATION_FAILED`，只有 VALUE 可有 numeric value。

`eval_manual_review(id, run_id, sample_ordinal, metric_key, observation_revision, reviewer,
manual_value, verdict, reason, created_at)` 只 insert。最终采用值查询取最新有效 review，否则取自动聚合；不在
observation 行上加 mutable override 列。

### 3.6 baseline

`eval_baseline(dataset_revision_id, fixture_hash, baseline_run_id, decided_by, decided_at)`；baseline run 必须
终态完整、manifest 身份齐全且 dataset/fixture 匹配。更新指针保留数据库审计日志或历史表，不能无痕覆盖。

## 4. 索引与保留

- run：`(created_at desc, id desc)`、`(dataset_revision_id, status, created_at desc)`。
- sample：`(run_id, status, ordinal)`；metric：`(run_id, metric_key, status)`。
- retention 仅批量清理已到期 recording 正文与报告 artifact；不得级联删除 sample/dataset/run/review。
- 所有正文列禁止进入普通日志、列表响应和诊断表。

## 5. 迁移顺序

V12 只创建上述表、check、索引和外键，不修改 V7～V11 问答事实表。`turn_id` 外键到 `qa_turn` 使用
`on delete restrict`；问答逻辑删除不破坏评测追溯。若后续需要 Prompt/evidence identity 补列，单独 V13
修改 Phase 3 表，并先用回填/兼容读取保护已有数据，不塞进 V12。
