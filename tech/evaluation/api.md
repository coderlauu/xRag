# API 契约：RAG 评测体系

- `status`: Step C review
- `date`: 2026-08-13
- `architecture`: [架构](architecture.md)
- `data_model`: [数据模型](data-model.md)

## 1. 接口原则

- 路径前缀 `/api/evaluations`，只面向当前单用户管理/CI 环境，不暴露给普通问答用户。
- 命令幂等键使用 `Idempotency-Key`；异步命令返回 `202` 与资源 ID。
- 列表不返回问题、reference、contexts 或 response；正文只在单样本详情且未清理、当前数据资格允许时返回。
- 所有错误使用项目既有 ProblemDetail；错误码稳定，Provider 原始错误不透传。
- CLI 是这些 HTTP 接口和 artifact 下载的薄客户端，不拥有第二套业务逻辑。

## 2. 数据集

| Method | Path | 语义 |
|---|---|---|
| POST | `/datasets:validate` | 校验草稿，返回错误/警告与 canonical hash，不落正式 revision |
| POST | `/datasets` | 发布不可变 revision |
| GET | `/datasets` | 分页列出 dataset revision，不返回正文 |
| GET | `/datasets/{revisionId}` | manifest 与成员元数据；`includeContent=true` 仅受控调用 |
| POST | `/datasets/{revisionId}:retire` | 逻辑废弃，已有 run 不受影响 |

发布请求包含 dataset key/description 与有序 samples；sample 含 PRD §5.2 字段。服务端生成 UUID、revision、
canonical hash，客户端不得指定可信 hash。

## 3. 运行

| Method | Path | 语义 |
|---|---|---|
| POST | `/runs:preview` | 校验 dataset/fixture/mode/预算/资格，返回预计样本与阻塞项 |
| POST | `/runs` | 创建并异步启动 run |
| POST | `/runs/{runId}:cancel` | 幂等取消；阻止新调用并传播当前取消 |
| GET | `/runs` | 分页查询模式、状态、计数、预算摘要 |
| GET | `/runs/{runId}` | manifest、状态、计数、预算、可比性与 artifact 链接 |
| GET | `/runs/{runId}/samples` | 分页逐样本状态与指标摘要 |
| GET | `/runs/{runId}/samples/{ordinal}` | 受控详情、turn/trace、事实、正文和原始 observations |

`POST /runs` 请求必须带 dataset revision、fixture identity、mode、sample filter 和四类 budget。真实模式还要
`confirmPaidExecution=true`；CI mode 若该值为 true 或声明真实模型直接 422。

终态：`REPORTED/PARTIAL/FAILED/CANCELED/BUDGET_EXHAUSTED`。HTTP 成功只表示命令被接受，不表示评测通过。

## 4. 评分、报告与比较

| Method | Path | 语义 |
|---|---|---|
| POST | `/runs/{runId}:score` | 对 RECORDED/PARTIAL run 评分；可选 metric policy/Judge repetitions |
| POST | `/runs/{runId}:render` | 从事实源生成或重建 artifact |
| GET | `/runs/{runId}/artifacts/{kind}` | 下载 manifest/jsonl/csv/markdown/failures |
| POST | `/comparisons` | 比较 before/after 两个 run |
| GET | `/comparisons/{id}` | 可比性、identity differences、paired delta 与样本变化 |
| POST | `/baselines` | 项目所有者将合格 run 设为 baseline |

score 请求不能切换 run 已冻结的 evaluator manifest；要换 Judge/Prompt/metric revision，创建新的 score
execution/revision，保留旧 observations。比较状态：`STRICTLY_COMPARABLE/NOT_STRICTLY_COMPARABLE`。

## 5. 人工复核

| Method | Path | 语义 |
|---|---|---|
| GET | `/runs/{runId}/reviews:export` | 导出待复核 CSV，不含未授权正文 |
| POST | `/runs/{runId}/reviews` | 追加单条/批量 review，引用具体 observation revision |
| GET | `/runs/{runId}/reviews` | 审计列表 |

review 需要 sample ordinal、metric key、manual value/verdict、reason、reviewer。更新已有 review 返回 405；修正
必须追加新行。修改 gold/reference 不走 review，而是发布新 sample revision。

## 6. 关键错误码

| HTTP | Code | 含义 |
|---:|---|---|
| 404 | `EVAL_DATASET_NOT_FOUND` / `EVAL_RUN_NOT_FOUND` | 资源不存在或已不可见 |
| 409 | `EVAL_REVISION_CONFLICT` | dataset 发布 revision 冲突 |
| 409 | `EVAL_RUN_STATE_CONFLICT` | 当前状态不允许 score/render/baseline |
| 409 | `EVAL_REVIEW_STALE_OBSERVATION` | review 引用的 observation revision 已非目标版本 |
| 422 | `EVAL_FIXTURE_NOT_READY` | 文档/向量/意图树未就绪 |
| 422 | `EVAL_BUDGET_REQUIRED` | 真实模式预算不完整 |
| 422 | `EVAL_REAL_EXECUTION_FORBIDDEN` | CI mode 试图使用真实 Provider |
| 422 | `EVAL_EXTERNAL_JUDGE_FORBIDDEN` | 样本无外部发送授权 |
| 422 | `EVAL_EVIDENCE_MISSING` | 目标 metric 缺必要证据 |
| 429 | `EVAL_RUN_CAPACITY_EXHAUSTED` | 同时运行数已满；不排无限队列 |

## 7. CLI 契约

仓库脚本提供 `dataset validate/publish`、`run preview/start/watch/cancel`、`score`、`report`、`diff`、
`review import/export`。脚本只组装请求、轮询和下载；退出码：0 成功/通过，2 命令或校验错误，3 run 失败，
4 review required，5 budget exhausted。秘密只读环境变量，不写命令行历史。
