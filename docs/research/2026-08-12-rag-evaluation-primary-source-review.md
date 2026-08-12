# Phase 4 RAG 评测体系一手资料复核

- 日期：2026-08-12
- 范围：评测样本与版本身份、分阶段指标边界、参考数据等级、LLM-as-judge、费用与可靠性、
  RAGAS 版本/前提/中文适用性，以及第一版交付形态
- 对应计划：[Phase 4 RAG 评测体系](../exec-plans/active/2026-08-12-rag-evaluation-phase-plan.md) §5.2
- 研究性质：Step A 事实基线；本文不冻结 Phase 4 的 Schema、API、阈值、框架选型或界面
- 来源边界：只采用 RAGAS 官方文档/固定版本源码/论文、必要的原始论文和官方规范；未采用二手博客

本文把每节结论显式分为三类：

- **来源事实**：一手来源直接支持的内容；
- **工程推断**：从来源事实推出、但来源没有原文承诺的含义；
- **对 xRag 的建议**：等待 Step B/C 裁决和落模的方案，不冒充外部事实。

## 结论摘要

1. **RAGAS 的样本结构不是可复现运行身份。** v0.4.3 的 `SingleTurnSample` 能装问题、答案、检索
   上下文/ID、参考答案/上下文和 rubric，`EvaluationDataset` 只有样本、backend 和 name；它不内建
   数据集 revision、知识库 revision、被测模型/Prompt/代码版本。xRag 必须在框架外建立不可变 manifest。
2. **样本身份、数据集身份和运行身份应分开。** 建议用规范化内容哈希标识样本 revision，用有序样本
   revision 清单哈希标识数据集 revision，再由运行 manifest 绑定知识库、代码、被测模型、业务 Prompt、
   评审模型、评审 Prompt 和指标实现。否则同名“数据集”和同名“模型”的分数不可比较。
3. **指标只能解释它实际观察到的阶段。** 检索 precision/recall 不能证明答案正确；Faithfulness 只判断
   答案是否由已检索上下文支持，不能证明上下文本身为真；Answer Relevancy 明确不判断事实准确性；
   Factual Correctness 要依赖 reference。任何单一“RAG 总分”都会掩盖退化来源。
4. **无参考评测是代理证据，不是事实金标。** 它可快速发现不忠实、答非所问、上下文利用/排序问题，
   但不能证明检索未漏、答案真实或业务任务完成。RAGAS 原论文也把这些指标称为 correctness 的 proxies。
5. **LLM 生成的 query/reference/label 属于弱标注。** RAGAS 能从知识图谱场景生成 query、context 和
   reference，适合扩大覆盖；因为题目和答案仍由模型制造，必须保留生成器与源文档身份，并用人工金标
   校准，不能直接升级为发布闸门真值。
6. **LLM-as-judge 必须视为一个有版本、有偏置、有费用的被测组件。** RAGAS 论文明确承认同配置多次
   运行可能不同；v0.4.3 的 LLM 基类默认温度并非严格零。原始 judge 研究还观察到位置、冗长、
   自我偏好和推理能力限制。只保存最终均分无法复盘这些误差。
7. **RAGAS 的中文能力是“可适配”，不是“已被中文验证”。** 当前官方文档要求非英语场景适配指标
   Prompt；默认只翻译 few-shot examples，instruction 仍是英语。原始 WikiEval 实验由英语流利标注者完成，
   没有给出中文有效性证据。xRag 必须做中文人工对齐集。
8. **RAGAS 版本风险真实存在。** 截至查证时 GitHub Latest 为 `v0.4.3`（tag commit `4ecab38`）；
   v0.3→v0.4 改了实验入口、指标包路径、评分调用、返回类型、LLM/Embedding 工厂、Prompt 系统和
   `ground_truths → reference`。若采用，应精确 pin 版本并隔离在适配器后。
9. **费用、性能、可靠性不是质量指标。** RAGAS 默认不会为 `evaluate()` 计算 token usage，需显式
   `TokenUsageParser`；业务链路还要独立记录检索、工具、生成、流式首块、排队、错误、取消和重试。
10. **第一版不需要因采用 RAGAS 而建设评测页面。** RAGAS 官方工作流支持实验结果落 CSV，并把元数据
    作为结果字段记录。对 xRag 第一版，版本化报告产物 + 可读取的运行/样本结果 API 足以形成证据闭环；
    只有人工复核/标注成为高频工作流时，页面才有明确用户价值。此项仍须项目所有者裁决。

## 1. 查证版本与证据边界

### 1.1 来源事实

- 查证时 RAGAS GitHub Releases 把 [`v0.4.3`](https://github.com/vibrantlabsai/ragas/releases/tag/v0.4.3)
  标为 Latest，tag 指向 commit
  [`4ecab384fda829ca50bec3f07cc49589d756e172`](https://github.com/vibrantlabsai/ragas/commit/4ecab384fda829ca50bec3f07cc49589d756e172)。
- v0.4.3 的 [`pyproject.toml`](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/pyproject.toml)
  要求 Python `>=3.9`，核心依赖中包含 `datasets`、`pydantic`、`openai`、`instructor` 和 LangChain
  生态依赖。这说明它不是可直接嵌进当前 Java 进程的零依赖指标包。
- 官方 [v0.3→v0.4 migration guide](https://docs.ragas.io/en/stable/howtos/migrations/migrate_from_v03_to_v04/)
  把 v0.4 称为向 experiment-based architecture 的根本迁移，并列出 7+ 个主要 breaking changes：
  `evaluate()` → `@experiment()`、`ragas.metrics` → `ragas.metrics.collections`、sample-based scoring →
  keyword arguments、`float` → `MetricResult`、LLM/Embedding 工厂与 Prompt 系统变化，以及
  `ground_truths: list[str]` → `reference: str`。
- RAGAS 原始 EACL 论文研究的是三个 reference-free 代理维度：faithfulness、answer relevance 和
  context relevance；实验中的评审调用使用 `gpt-3.5-turbo-16k`。它不是当前 v0.4.3 全部指标行为的
  规格书。[Es et al., 2024](https://aclanthology.org/2024.eacl-demo.16/)

### 1.2 工程推断

- “RAGAS 论文验证过”不能等价为“v0.4.3 的所有指标、所有 Judge 和所有语言都已验证”。论文、当前
  文档和固定 tag 源码必须作为三类不同证据记录。
- 引用 `stable` 文档只说明查证时的公开说明；可复现实验必须同时保存实际安装版本、commit/tag 和指标
  Prompt，因为 `stable` 会随发布移动。

### 1.3 对 xRag 的建议

- Step B 只把 RAGAS 保留为候选，不在事实复核阶段定案。
- 若进入 PoC，固定 `ragas==0.4.3`（或届时重新复核的新固定版本），禁止直接跟随 `main` / 无上限
  `pip install ragas`；把 Python 运行时和依赖锁文件纳入运行身份。
- 自有评测领域模型和存储结构不得直接暴露 RAGAS 类名，避免下一次 breaking migration 扩散到业务域。

## 2. 样本、数据集和一次运行的不可变身份

### 2.1 来源事实：RAGAS 实际保存什么

固定到 v0.4.3 的 [`SingleTurnSample` 源码](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/dataset_schema.py#L55-L98)
包含：

- `user_input`；
- `retrieved_contexts` / `reference_contexts`；
- `retrieved_context_ids` / `reference_context_ids`；
- `response` / `multi_responses` / `reference`；
- `rubrics`、`persona_name`、`query_style`、`query_length`。

同一固定源码中的 [`EvaluationDataset`](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/dataset_schema.py#L303-L378)
只在样本集合之外增加 `backend` 和 `name`。这些类没有定义 `sample_id`、`sample_revision`、
`dataset_revision`、知识库 revision、模型身份、业务 Prompt 版本或代码 commit。

RAGAS 的 [Experimentation 官方文档](https://docs.ragas.io/en/stable/concepts/experimentation/)建议把
`git_commit`、`environment`、`model_version`、token 和响应耗时作为返回结果的附加 metadata；这是一种
由调用方主动填入的范式，不是上述样本 Schema 自动保证的身份约束。migration guide 也只把 git
integration 描述为 optional。

RAGAS v0.4 Prompt API 可以保存 Prompt，并在文件中记录 `ragas_version`、`language`、`original_hash`、
instruction 和 examples；加载不同版本的 Prompt 时会警告可能不兼容。
[Prompt API](https://docs.ragas.io/en/latest/references/prompt/)

### 2.2 工程推断：三个身份不能合并

建议区分：

```text
sample_revision_id
  = hash(canonical user input + label type + immutable references/rubric)

dataset_revision_id
  = hash(dataset metadata + ordered sample_revision_id manifest)

evaluation_run_id
  = hash(dataset revision + knowledge-base revision + SUT manifest
         + evaluator manifest + run policy)
```

- `sample_revision_id` 只标识评测题与标注事实，不包含本次系统输出；改参考答案、相关 chunk、期望意图、
  工具调用或 rubric 就是新的 sample revision。
- `dataset_revision_id` 标识本次题集成员与顺序/权重；只写 `dataset_name` 无法区分加题、删题或改标注。
- `evaluation_run_id` 标识“拿哪一版系统在什么条件下跑哪版题集”；它不能反过来替代稳定 sample ID。
- 同一 run 下 LLM Judge 的多次重复必须有 `attempt/repetition_index`，否则均值无法追溯到原始判断。

这套哈希形式是 xRag 的工程推断，不是 RAGAS 官方 Schema 要求。

### 2.3 对 xRag 的建议：运行 manifest 的最低绑定

| 维度 | 至少绑定的不可变事实 |
|---|---|
| 评测集 | dataset ID/revision、sample ID/revision、label provenance、split、内容/manifest hash |
| 知识库 | corpus/revision、文档与 chunk manifest、解析/切分配置、Embedding 实际模型与维度、索引快照 |
| 被测系统 | Git commit/build ID、配置快照、意图/改写/检索/精排/工具/生成版本 |
| 被测模型 | Provider、请求模型、响应实际模型、区域/endpoint、采样参数、业务 Prompt hash |
| 评审器 | 框架版本+commit、metric class+参数、Judge/Embedding 实际模型、评审 Prompt artifact/hash |
| 运行策略 | run ID、开始时间、环境、超时/并发/重试、seed（若 Provider 支持）、重复次数 |
| 计量 | 每阶段耗时、input/output tokens、请求数、价格表版本、货币、计算出的费用 |

不要把文档正文、用户问题或 Prompt 原文为了“可复现”无条件写入遥测；身份可用受控 artifact ID/hash，
正文是否保存仍受 Phase 4 数据授权与保留策略约束。

## 3. 意图、检索、工具、生成和端到端的指标边界

### 3.1 来源事实：RAGAS 指标各自看见什么

| 指标 | 所需主要输入 | 它直接衡量的内容 | 它不证明什么 |
|---|---|---|---|
| Context Precision | query + retrieved contexts + reference，或无 reference 变体使用 response | 相关 chunk 是否排在前面；当前公式按排名累计 precision | 答案是否正确、是否漏掉未检索的相关文档 |
| Context Recall | retrieved contexts + reference/reference contexts/IDs | reference 中的信息或相关 ID 有多少被检索覆盖 | 最终回答是否忠实、是否满足用户 |
| Faithfulness | response + retrieved contexts | response claims 是否能由给定上下文推出 | 上下文本身是否真实、答案是否完整或答题 |
| Answer Relevancy | user input + response + Judge + Embedding | 从答案反推问题后与原问题的 embedding 相似度 | 官方明确：不评估 factual accuracy |
| Factual Correctness | response + reference | 两边 claim 的事实重合，按 precision/recall/F1 | reference 是否权威、检索是否正确 |
| Noise Sensitivity | input + response + reference + retrieved contexts | 错误 claims 与相关/无关上下文的关系 | 没有 reference 时不能成立 |
| ToolCallAccuracy | actual conversation/tool calls + reference tool calls | 工具序列、名称和参数匹配；可选 strict/flexible order | 工具执行结果是否真实完成业务目标 |

直接来源：

- [Context Precision](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/)
- [Context Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)
- [Faithfulness](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/faithfulness/)
- [Answer Relevancy](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/answer_relevance/)
- [Factual Correctness](https://docs.ragas.io/en/latest/concepts/metrics/available_metrics/factual_correctness/)
- [Noise Sensitivity](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/noise_sensitivity/)
- [Agent/Tool metrics](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/agents/)

RAGAS 还提供纯 ID 的 Context Precision/Recall：比较 `retrieved_context_ids` 与
`reference_context_ids`，不调用 LLM。官方把它定位为已有唯一文档 ID 时的直接高效方式。
[ID-based Precision](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/)、
[ID-based Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)

### 3.2 工程推断：阶段矩阵

| 阶段 | 第一性问题 | 适合的证据 | 不应跨用的证据 |
|---|---|---|---|
| 意图 | 路由标签是否正确、边界类如何混淆 | 人工 label 的 accuracy、macro-F1、逐类 P/R、confusion matrix、拒答/未知覆盖 | Answer Relevancy 不能替代意图分类正确率 |
| 检索/精排 | 相关证据是否取全、排前、稳定 | gold chunk/doc ID 的 Recall@K、Precision@K、MRR/nDCG；无 gold 时 LLM relevance 只作代理 | Faithfulness 高不代表召回高；答案可能只利用漏召回后的片面证据 |
| 工具 | 是否选择正确工具、参数、顺序并成功执行 | reference tool calls 的结构匹配 + 执行结果/副作用断言 | 只看工具名准确率不能证明任务完成 |
| 生成 | 是否由证据支持、回答问题、符合 gold 与 rubric | Faithfulness、Answer Relevancy、reference-based correctness、人工 rubric 分项 | 不把检索 precision 当答案正确率；不把文本相似度当事实真值 |
| 端到端 | 用户目标是否完成且总体可接受 | 任务成功率、人工偏好/rubric、错误类型分布；同时展示各阶段分数 | 不把端到端偏好分反投影成某个阶段根因 |

### 3.3 对 xRag 的建议

- 第一版 dashboard/report 顶层按“意图—检索—工具—生成—端到端—性能/费用/可靠性”分栏，不产生一个
  加权总分。
- 优先建设稳定的 chunk/document ID gold retrieval 指标；LLM Context Precision/Recall 作为补充，
  不覆盖确定性 ID 指标。
- 每个指标定义必须写 `required evidence` 和 `does not prove`，从数据模型层防止跨阶段误读。
- 回归比较优先在同一批样本上做 paired delta，并保留 sample-level 结果；只比较两个总均分会丢失
  “哪些样本变好/变坏”的定位信息。

## 4. 无参考、弱标注与人工金标

### 4.1 来源事实

RAGAS 原论文明确说它在没有 human-annotated dataset/reference answer 时估计不同的 correctness proxies，
并把 Faithfulness、Answer Relevance、Context Relevance 拆开。
[论文 §3](https://aclanthology.org/2024.eacl-demo.16.pdf)

当前 RAGAS 文档展示了不同证据要求：

- 无 reference 可运行 Faithfulness、Answer Relevancy，以及用 response 判断上下文是否有用的
  Context Utilization；[Context Precision 文档](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_precision/)
- Context Recall “always requires a reference”，LLM 版本可把 reference answer 当 reference contexts 的
  proxy；[Context Recall 文档](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)
- Factual Correctness 直接比较 response 与 reference；
  [Factual Correctness 文档](https://docs.ragas.io/en/latest/concepts/metrics/available_metrics/factual_correctness/)
- RAGAS 的 TestsetGenerator 可用知识图谱、场景、query synthesizer 从文档生成 query、reference contexts
  和 reference；[Testset Generation for RAG](https://docs.ragas.io/en/stable/concepts/test_data_generation/rag/)
- 官方对理想评测集的要求包括真实场景覆盖、足够样本量和持续更新以应对 data drift；
  [Testset Generation](https://docs.ragas.io/en/stable/concepts/test_data_generation/)

原论文自己的 WikiEval 也不是“完全无人工”：它从 50 个 2022 年后的 Wikipedia 页面生成问题/答案，
再由两名英语流利标注者做三维 pairwise judgment；faithfulness/context relevance 约 95% 一致，answer
relevance 约 90%，分歧经讨论解决。最终报告的 RAGAS 与人类 pairwise accuracy 分别为 0.95、0.78、
0.70。这个实验说明在该小型英语构造集上有对齐证据，不是对任意领域/语言的普遍保证。
[论文与 WikiEval 结果](https://aclanthology.org/2024.eacl-demo.16.pdf)

### 4.2 工程推断：三类数据能证明到哪里

| 数据等级 | 可以支撑 | 不能单独支撑 |
|---|---|---|
| 无参考/生产抽样 | 快速发现明显不忠实、答非所问、上下文利用差；观察真实分布 | 事实正确、检索完整、release pass/fail 真值 |
| 弱标注/合成 | 低成本扩展主题、hop、persona、边界场景；形成 smoke/regression 候选 | 独立于生成器/知识库偏差的业务金标 |
| 人工金标 | 在已定义 rubric 和版本范围内校准指标、Judge 与发布阈值 | 未覆盖主题/未来知识库 revision 的普遍正确性 |

特别地：

- “忠实但错误”完全可能：若检索上下文本身含错误，Faithfulness 可以很高。
- “相关但错误”完全可能：Answer Relevancy 官方定义排除了 factual accuracy。
- 用 response 判断 Context Utilization 会把生成器行为混进检索标签：遗漏的信息没有出现在 response 中，
  就可能被错误地视为“不需要”。
- 用 LLM 生成 reference 再用相同/同族 Judge 评分，会形成相关误差；它不是独立证据。

### 4.3 对 xRag 的建议：防污染护栏

1. 给每个 label 标 `human_gold / expert_adjudicated / synthetic / llm_judged / heuristic / production_unlabeled`
   和来源版本，不允许默认为 gold。
2. 固定 train/dev/release-holdout 三个 sample ID 集；Prompt/阈值调优只看 train/dev，release holdout
   只在守门运行打开，不把其逐题答案放进业务 Prompt、few-shot、合成器或知识库。
3. 对题目、reference、source chunk 做规范化 hash/近重复检查，检测不同 split 之间以及评测集与
   业务 few-shot/检索 corpus 的直接泄漏。
4. 任何被人工纠正的样本生成新 sample revision；不原地覆盖历史运行所引用的标注。
5. 定期加入新时间窗/新知识库 revision 的 sealed holdout，防止团队长期针对同一静态集合过拟合。
6. 发布闸门以人工金标核心集为准；弱标注和无参考集负责扩大观察面并触发人工复核，不独立宣告正确。

这些具体污染控制是对 xRag 的建议；RAGAS v0.4.3 的上述样本 Schema 不会自动实施它们。

## 5. 离线质量、在线性能、费用与可靠性的分层

### 5.1 来源事实

RAGAS 官方费用文档明确：`evaluate()` **默认不计算 token usage**，因为不同 LangChain LLM 返回 usage
的方式不统一；调用方要提供 `TokenUsageParser`，之后才可调用 `total_tokens()`，并传单价给
`total_cost()`。[Understand Cost and Usage](https://docs.ragas.io/en/stable/howtos/customizations/metrics/_cost/)

OpenTelemetry GenAI 语义约定区分请求模型与响应实际模型，并定义了 request temperature/seed、
response model、time-to-first-chunk、retrieval document ID/score、input/output token 等属性；同时明确
提示 input messages 和 retrieval query 可能含敏感信息。
[OpenTelemetry GenAI registry](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)
这些约定目前已迁移到独立的
[OpenTelemetry GenAI Semantic Conventions 仓库](https://github.com/open-telemetry/semantic-conventions-genai)，
因此实现时仍需固定所采用的 convention 版本。

### 5.2 工程推断：一次退化的定位顺序

```text
同一 dataset/KB/SUT/evaluator 身份？
  ├─ 否：先归因于不可比运行，不解释分差
  └─ 是
      ├─ 意图/改写变化？
      ├─ 候选召回变化？
      ├─ 精排顺序变化？
      ├─ 工具选择/结果变化？
      ├─ 证据相同但生成变化？
      └─ 质量相近但 latency/cost/error 变化？
```

建议至少分四个独立事实面：

| 事实面 | 样例字段/聚合 |
|---|---|
| 离线质量 | 各阶段 sample-level score、均值/分位数、失败标签、人工分歧 |
| 在线性能 | queue、rewrite、retrieve、rerank、tool、TTFT、generation、total 的 P50/P95/P99 |
| 费用 | 每阶段请求数、input/output/cache/reasoning token、实际模型、价格表版本、金额 |
| 可靠性 | success/error/timeout/cancel/parse failure/empty answer、retry/failover、断连恢复 |

质量分不能解释 latency，token 费用不能解释任务成功，HTTP 成功也不能解释答案质量；四者只能在同一个
run/sample trace 中关联，不能互相替代。

### 5.3 对 xRag 的建议

- 离线评测 run 保存冻结输入/输出和分项结果；生产遥测只在授权范围保存内容，默认优先保留 ID、耗时、
  tokens、错误与阶段诊断。
- CI 只跑固定、有限、可预算的核心集；真实 Judge 调用必须设置样本上限、调用上限、token/金额上限和
  总截止时间，超预算应是明确失败/跳过状态，而不是继续无界调用。
- 价格不是模型的永恒属性：把 `pricing_version/effective_at/currency` 与原始 tokens 同时保存，允许以后
  按当时价格复算。

## 6. LLM-as-judge 的随机性、偏置、版本与费用

### 6.1 来源事实

RAGAS 原论文专设 Reproducibility 小节：同一配置的重复运行可能因底层 API 未公开变化或神经网络固有
随机性而给出不同结果；结构化 JSON 能提高连续运行的一致性，但论文没有声称消除随机性。
[论文 §5.1](https://aclanthology.org/2024.eacl-demo.16.pdf)

固定到 v0.4.3 的 [`BaseRagasLLM`](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/llms/base.py#L68-L120)
中，单 completion 默认 temperature 为 `0.01`；`get_temperature(n)` 在 `n > 1` 时返回 `0.3`。具体
adapter 还可能因模型不支持而 bypass temperature 或 `n`。因此“使用 RAGAS”本身不提供严格确定性。

RAGAS 的 AspectCritic/通用 judge 指标提供 strictness/多次 verdict 与多数投票思路；多次调用增加的
一致性是以额外请求和费用换来的。
[General Purpose Metrics](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/general_purpose/)

RAGAS 官方 judge alignment 指南要求先用专家标注样本衡量 Judge、分析分歧并迭代 Prompt，并明确把
Judge 定位为扩展分析的工具而非专家替代品。
[Align an LLM as a Judge](https://docs.ragas.io/en/v0.3.8/howtos/applications/align-llm-as-judge/)

独立的原始 LLM-as-judge 研究观察到：

- position bias、verbosity bias、自我偏好可能性和有限推理能力；
- 交换候选顺序是位置偏置的缓解方法，但需要两次调用；
- few-shot 在其实验中提高一致性，同时让 API 调用约贵 4 倍，且高一致性不等于高准确；
- reference-guided grading 在数学小样本中把 failure rate 从 70% 降到 15%，说明 rubric/reference 会改变
  Judge 能力边界。

来源：[Zheng et al., 2023, Judging LLM-as-a-Judge](https://arxiv.org/abs/2306.05685)。该研究对象是开放式
聊天模型比较，不应把其中具体比例直接外推为 xRag 的中文 RAG Judge 准确率。

### 6.2 工程推断

- Judge 的“模型名”不足以定义身份：同一别名可能映射到变化的实际模型；至少要同时记录请求模型和
  响应模型/版本指纹（Provider 能提供什么就保存什么）。
- Judge Prompt 是测量仪器的一部分；改 instruction、few-shot、语言适配、claim granularity、rubric 或
  输出解析器，就产生新的 metric revision，旧分数不应直接拼接成同一时间序列。
- 多数投票可以降低部分随机方差，但不能消除系统性偏置；相同 Judge/Prompt 重复三次不会创造独立真值。
- 同族模型评自己或评由同族模型生成的 weak label 有相关偏差风险；需用人工对齐集观察，而不是假设中立。

### 6.3 对 xRag 的建议：Judge manifest 与守门

每个 Judge score 至少记录：

- metric name/revision、rubric、RAGAS version/commit、metric 参数；
- Prompt artifact ID/hash、language、adaptation model 与 adaptation 配置；
- Provider、请求/响应实际 Judge model、endpoint/region、temperature/top-p/seed/strictness；
- evaluator embedding model/维度（Answer Relevancy 等依赖 embedding 的指标）；
- repetition index、原始离散 verdict/理由、解析/重试状态；
- input/output token、调用数、单价版本、金额、耗时。

守门顺序建议：先用小型中文人工集校准 Judge；再设 per-metric 一致率/分歧率与费用上限；分数接近阈值、
重复不一致、涉及数字/日期/否定/多跳/工具副作用的样本进入人工复核。第一版不把一次 Judge 均分直接设为
不可申诉的 release verdict。

## 7. RAGAS 的当前适用性、中文风险与自有指标边界

### 7.1 来源事实

- 官方非英语适配文档要求对目标语言适配 metrics；RAGAS 用 LLM 翻译 few-shot examples，默认
  `adapt_instruction=False`，即 instruction 仍保留英语。Faithfulness 和 FactualCorrectness 各有多个
  Prompt，需要逐一适配。[Adapting Metrics to Target Language](https://docs.ragas.io/en/latest/howtos/customizations/metrics/metrics_language_adaptation/)
- Answer Relevancy 除 Judge 外还依赖 embedding，并以默认 3 个反推问题与原问题的 cosine similarity
  平均值计分；官方同时注明 cosine 数学范围使其不保证严格落在 0~1。
  [Answer Relevancy](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/answer_relevance/)
- 原始 WikiEval 的两位人工标注者明确是英语流利者，实验 Prompt 使用英语，数据源是英语 Wikipedia；
  论文没有中文实验。[Es et al., 2024](https://aclanthology.org/2024.eacl-demo.16.pdf)
- `v0.4.3` release notes 包含“fix: enable FactualCorrectness language adaptation”，说明语言适配行为本身也
  在版本间变化。[v0.4.3 release](https://github.com/vibrantlabsai/ragas/releases/tag/v0.4.3)

### 7.2 工程推断

“接口支持中文字符串”与“指标在 xRag 中文领域上有效”是两件事。至少存在：

- 中文 claim 切分、代词省略、否定、数字/单位/日期归一带来的 Faithfulness/FactualCorrectness 偏差；
- 中英混合产品名、代码、专有名词对 Prompt adaptation 与 embedding similarity 的影响；
- Judge 的中文推理能力和 adapted examples 翻译质量；
- reference answer 不完备时，Factual Correctness 把正确的补充信息判成 false positive；
- chunk ID 粒度随重新切分变化，使 ID-based retrieval gold 跨知识库 revision 失效。

这些是需要实测的风险，不是一手来源已经证明的中文失败结论。

### 7.3 对 xRag 的建议

RAGAS 更适合作为“可插拔的 LLM/Embedding 代理指标集合”，不适合作为完整评测真相层。第一版建议：

- **自有确定性指标**：意图 confusion matrix、Recall@K/Precision@K/MRR/nDCG、tool call 结构/执行断言、
  latency/cost/reliability、协议/SSE/取消行为；
- **RAGAS 候选指标**：Faithfulness、Answer Relevancy、reference-based Factual Correctness，先做中文
  expert alignment；Context Precision/Recall 仅在 gold 不足时补充 LLM proxy；
- **人工指标**：有用性、完整性、引用可验证性、拒答恰当性、业务任务成功及边界案例；
- 保存适配后的每一份 Prompt，而不是每次运行临时翻译；对中文、数字、日期、表格、否定、多跳分别
  抽样校准；
- 框架升级、Judge/Embedding 变更或 Prompt 变化时，先在固定 alignment set 上双跑，再决定是否迁移
  baseline，禁止把新旧 evaluator 分数直接接线。

## 8. 第一版是否需要评测页面

### 8.1 来源事实

RAGAS 的 Experimentation 文档展示了将实验结果自动保存到带时间戳的 CSV，并把 `git_commit`、
`model_version`、tokens 和 latency 作为结果 metadata；官方 RAG 应用示例还使用 MLflow trace 辅助调试。
[Experimentation](https://docs.ragas.io/en/stable/concepts/experimentation/)、
[Evaluate and Improve a RAG App](https://docs.ragas.io/en/stable/howtos/applications/evaluate-and-improve-rag/)

这些来源说明“报告/实验 artifact + trace”是可运行工作流，但没有为 xRag 证明必须有或不应有自建 UI。

### 8.2 工程推断

Phase 4 第一版的核心不变量是可复现、可比较、可定位和可审核；页面只是这些事实的一个消费者。若数据
身份、样本明细和原始分项结果尚未稳定，先建页面会把不稳定口径固化成 UI。

### 8.3 对 xRag 的建议（提交项目所有者裁决）

**建议第一版不建专用评测页面**，先交付：

- 可版本化、可下载的 run manifest；
- 机器可读的 sample-level 结果与聚合报告；
- 受控的运行/结果查询 API 或 CLI；
- 指向既有 trace/日志的诊断链接；
- 人工复核可先使用导出表/受控文档完成。

当出现下列任一明确需求时再进入 UI：多人持续标注/复核、样本分歧仲裁、跨 run drill-down、非工程角色
自助发起评测、审批式 release gate。这个建议不替代项目所有者在 Step B 的显式裁决。

## 9. 对 Phase 4 §5.2 七个问题的直接回答

| 问题 | Step A 回答 |
|---|---|
| 1. 最小不可变身份 | 分离 `sample_revision_id`、`dataset_revision_id`、`evaluation_run_id`；运行 manifest 绑定 KB、SUT、模型、Prompt、Judge、代码与费用版本。RAGAS Schema 不自动提供完整身份。 |
| 2. 分阶段指标 | 意图用 gold 分类指标；检索用 ID/ranking 指标；工具用结构+执行断言；生成拆 faithfulness/relevance/correctness；端到端用任务成功/人工 rubric。不得用一个总分互相替代。 |
| 3. 三类数据与污染 | 无参考只给代理信号；弱标注扩大覆盖但继承生成器偏差；人工金标用于校准和发布。以 split、hash/近重复检查、sealed holdout、不可变 revision 和访问隔离防污染。 |
| 4. 质量/性能/费用/可靠性 | 四个独立事实面，通过同一 run/sample trace 关联；先核对身份，再逐阶段比较 delta，最后看 latency/cost/error。 |
| 5. LLM-as-judge | 版本化模型、实际响应身份、Prompt、参数、重复、原始 verdict、Embedding、tokens 和价格；用中文人工集校准，预算和分歧触发人工复核。 |
| 6. RAGAS/中文 | v0.4.3 可做候选；原论文不是当前全指标规格，中文只有适配机制无有效性证明，版本 breaking change 明显。确定性指标与人工复核必须保留。 |
| 7. 是否要页面 | 来源不要求页面；建议 v1 先做报告 artifact + API/CLI + trace，待人工协作工作流成立再建 UI，由项目所有者在 Step B 裁决。 |

## 10. 一手来源清单

| 来源 | 用途 | 查证日期 |
|---|---|---|
| [RAGAS EACL 2024 paper](https://aclanthology.org/2024.eacl-demo.16/) | 原始三指标、WikiEval、人类一致性、重现性与论文限制 | 2026-08-12 |
| [RAGAS v0.4.3 release](https://github.com/vibrantlabsai/ragas/releases/tag/v0.4.3) | 最新固定发布、commit、语言适配修复 | 2026-08-12 |
| [v0.4.3 dataset schema](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/dataset_schema.py) | 样本/数据集实际字段与身份缺口 | 2026-08-12 |
| [v0.4.3 LLM base](https://github.com/vibrantlabsai/ragas/blob/v0.4.3/src/ragas/llms/base.py) | 默认 temperature、multiple completions、adapter 差异 | 2026-08-12 |
| [v0.3→v0.4 migration](https://docs.ragas.io/en/stable/howtos/migrations/migrate_from_v03_to_v04/) | breaking changes 与 experiment 架构 | 2026-08-12 |
| [RAGAS metrics index](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/) | 当前指标类别 | 2026-08-12 |
| [RAGAS Prompt API](https://docs.ragas.io/en/latest/references/prompt/) | Prompt 保存、版本、语言、hash | 2026-08-12 |
| [RAGAS language adaptation](https://docs.ragas.io/en/latest/howtos/customizations/metrics/metrics_language_adaptation/) | 非英语适配范围与默认行为 | 2026-08-12 |
| [RAGAS test generation](https://docs.ragas.io/en/stable/concepts/test_data_generation/rag/) | 合成 query/context/reference 的机制 | 2026-08-12 |
| [RAGAS cost](https://docs.ragas.io/en/stable/howtos/customizations/metrics/_cost/) | token parser 与费用计算前提 | 2026-08-12 |
| [RAGAS experimentation](https://docs.ragas.io/en/stable/concepts/experimentation/) | CSV artifact、调用方 metadata、实验分层 | 2026-08-12 |
| [Zheng et al., 2023](https://arxiv.org/abs/2306.05685) | LLM Judge 的位置/冗长/自我偏好/推理限制和缓解代价 | 2026-08-12 |
| [OpenTelemetry GenAI conventions](https://github.com/open-telemetry/semantic-conventions-genai) | 模型、检索、token、性能观测的官方语义约定 | 2026-08-12 |

## 11. 未被一手资料解决的缺口

1. **没有 RAGAS 中文 RAG benchmark 能证明 xRag 所需指标有效。** 必须由本项目中文人工对齐集实测。
2. **没有通用的一条 sample identity 标准。** 本文的三层 hash/manifest 是工程建议，需 Step C 落模。
3. **没有来源能替项目所有者决定页面。** 现有证据只说明无专用页面也能形成第一版实验闭环。
4. **Judge 单价与模型可用性会变化。** Step C 选择实际 Provider 后，要按当日官方价格和实际 usage
   响应再查，本文不写死金额。
5. **阈值、样本量和重复次数尚不能冻结。** 它们必须来自 xRag pilot 的分布、人工分歧、费用与可接受
   回归风险，不能从 WikiEval 或 MT-Bench 的比例直接移植。
