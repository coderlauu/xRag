# 技术方案：AI 知识问答核心链路（Phase 3A + 3B）

- `status`: 3A approved（2026-08-07）；**3B draft（2026-08-08，§18~§22，待 Step C 闸门确认）**
- `approved_at`: 2026-08-07
- `date`: 2026-08-07
- `scope`: 3A — 记忆 + 改写 + 意图；3B — 检索 + 工具（§18~§22）；3C 只冻结扩展边界
- `prd`: [Phase 3 PRD](../../docs/prd/2026-08-07-qa-pipeline-prd.md)
- `phase_plan`: [Phase 3 执行计划](../../docs/exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)
- `data_model`: [数据模型](data-model.md)
- `api`: [API 契约](api.md)
- `tests`: [测试矩阵](test-matrix.md)
- `related`: [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)、[ADR 0003](../../docs/adr/0003-separate-model-capability-ports.md)、[ADR 0005](../../docs/adr/0005-separate-model-runtime-state.md)、[ADR 0006](../../docs/adr/0006-bind-chat-sampling-to-service-tier.md)、[ADR 0007](../../docs/adr/0007-versioned-intent-tree.md)、[ADR 0008](../../docs/adr/0008-hybrid-retrieval-and-rrf-fusion.md)

## 1. 方案结论

3A 新建 `com.app.qa` 深模块。它负责持久化问答业务事实，并用 Phase 2 的 `ChatClient` 完成摘要、改写、
意图评分和灰区确认；不直接依赖 Provider、HTTP 协议或供应商参数。

```text
HTTP / Conversation + Route Canvas
                 │
                 ▼
┌──────────────────────────────────────────────────────────┐
│ com.app.qa                                                │
│ 保存本轮 → 历史快照 → 摘要 → 改写/拆分                  │
│        → 固定意图树 revision → 有界并行评分              │
│        → 总量封顶 → 歧义短路 / 检索动作                  │
└──────────────────────────────────────────────────────────┘
       │ PostgreSQL                     │ stable capability port
       ▼                                ▼
qa_* 业务表                    com.app.ai.chat.ChatClient
```

第一版明确采用：

- 同步 MVC 应用服务；数据库访问和同步 Chat 调用不放进 Reactor 链。
- PostgreSQL 保存对话、消息、摘要、本轮、处理结果和意图树 revision；业务删除一律逻辑删除。
- 最近 8 个完整轮次保留原文；可摘要历史达到 12 个完整轮次时，批量压缩更早历史。
- 意图树是稳定 UUID 邻接树，系统虚拟根下最多 3 层；可评分叶子只有 `KB` 和 `SYSTEM`。
- 每次树写入生成完整 revision；本轮从开始到终态只消费一个不可变 revision。
- 最多 3 个子问题进入专用有界线程池；每个子问题对同一份启用叶子快照只调用一次评分模型。
- 3A 所有 Chat 调用使用 `BALANCED`；catalog 解析为 `temperature=0.1`、`topP=0.3`。
- Prompt 是 classpath 中受版本控制的资源，启动时加载并校验，占位符或预算错误直接阻止启动。
- 3A 不执行真实检索、不生成最终答案、不提供 SSE、不实现停止或排队。

## 2. 边界与成功标准

### 2.1 模块负责

- 对话创建、列表、重命名、逻辑删除和游标分页消息读取。
- 用户消息先落库，本轮再进入理解链路；同一对话最多一个活动本轮。
- 摘要水位线、历史快照、旧摘要保留和并发摘要 CAS。
- 查询最小归一化、LLM 改写、最多 3 个子问题、结构化输出容错。
- 意图树增删改、子树启停/删除、知识库绑定、整树 revision 和版本冲突。
- 启用叶子快照、Prompt 编码预算、并行评分、候选校验和跨子问题封顶。
- 歧义判定、固定引导消息、用户选择闭环和声明式检索动作。
- 不含正文的本轮阶段记录，以及与 `com.app.ai` `logicalCallId` 的关联。

### 2.2 模块不负责

- 3B 的 Embedding、pgvector 检索、全局检索、结果融合、Rerank 或 MCP。
- 3C 的最终 Prompt、回答生成、SSE、取消、断连、排队或许可。
- 模型目录、Provider 适配、重试、熔断和模型诊断；这些仍属于 `com.app.ai`。
- Prompt 热更新、运营 Prompt 编辑、意图版本回滚、批量导入导出或多人协同发布。
- 多实例缓存失效、跨进程任务恢复或分布式锁。

### 2.3 可验证成功

1. 已返回“已保存”的用户消息在刷新和进程重启后仍存在。
2. 摘要只覆盖完整旧轮次，水位线与最近 8 轮没有重叠或缺口；摘要失败不删除旧摘要。
3. 改写失败退回最小归一化问题；超过 3 个真实子问题显式拒绝，不截断。
4. 在途请求固定一个意图 revision；运营写入不会让同一轮混用新旧节点。
5. 未知节点、越界分数、重复节点和非法 JSON 不能绕过代码校验。
6. 低置信度不会伪装成确定路由；歧义短路不会调用检索或答案生成。
7. `qa_*` 可以保存业务正文，`ai_*` 诊断和问答阶段记录都不保存 Prompt、问题、摘要或模型响应体。

## 3. 模块与依赖方向

```text
com.app.qa
├── model          # ConversationId、Turn、Message、SubQuestion、IntentMatch、RetrievalAction
├── conversation   # 对话/消息/本轮生命周期与查询
├── memory         # HistorySnapshot、SummaryPolicy、SummaryService
├── rewrite        # 最小归一化、RewriteService、结构化输出解析
├── intent         # revision、树约束、评分、封顶、歧义与动作映射
├── prompt         # classpath 模板、确定性渲染、UTF-8 预算
├── repository     # JdbcTemplate；一类主要负责一张表或一个明确聚合写入
├── service        # 同步 QuestionUnderstandingService 编排
└── web            # /api/qa/** DTO、ProblemDetail 映射

com.app.qa ──> com.app.ai.chat.ChatClient
com.app.qa ──> com.app.knowledge.repository（仅通过窄的 KB 可用性查询端口）
```

依赖纪律：

- `qa` 不引用 `ai.provider`、`ai.routing`、`ai.runtime` 或供应商 SDK。
- `ai` 不反向依赖 `qa`；`logicalCallId` 由能力结果返回后写入问答阶段记录。
- 知识库绑定校验通过 `KnowledgeBaseLookup` 窄端口完成，不让意图模块拼知识库 SQL。
- 所有模型调用都在数据库事务外；事务只覆盖本地状态转换或一份树 revision 的原子写入。
- controller 不实现树一致性、摘要水位或封顶算法；前后端共用服务端领域规则。

## 4. 本轮状态机与端到端流程

### 4.1 状态

```text
SAVED ──> PROCESSING ─┬─> ACTIONS_READY
                      ├─> WAITING_CLARIFICATION
                      ├─> REJECTED_TOO_MANY_TOPICS
                      └─> FAILED
```

- `SAVED`：用户消息与本轮已在同一事务提交，尚未调用模型。
- `PROCESSING`：理解链路执行中；不是可长期停留的稳定终态。
- `ACTIONS_READY`：每个子问题已有声明式动作，可被 3B 消费。
- `WAITING_CLARIFICATION`：已保存系统引导消息与候选，等待下一轮用户选择。
- `REJECTED_TOO_MANY_TOPICS`：明确告诉用户拆分问题；不保存被截断的子问题。
- `FAILED`：本轮没有可安全消费的动作；保留用户消息和稳定错误码。

终态单向写入，使用 `where status = 'PROCESSING'` CAS；重复回调不能覆盖已有终态。应用启动时把超过
`processing-stale-after` 的 `PROCESSING` CAS 为 `FAILED/PROCESS_INTERRUPTED`，不自动重放可能收费的调用。

### 4.2 提交流程

1. 校验 `clientRequestId` 和消息；若未给 `conversationId`，在同一事务创建对话。
2. 以 `(conversation_id, client_request_id)` 幂等创建用户消息和 `SAVED` 本轮。
3. CAS 为 `PROCESSING`；取得对话历史快照和当前意图 revision。
4. 如摘要条件满足，在事务外生成新摘要，再以旧 revision/水位线 CAS 安装；失败保留旧摘要。
5. 渲染改写 Prompt，调用一次 `ChatClient(BALANCED)`；解析失败使用最小归一化问题。
6. 若模型明确返回超过 3 个可独立问题，写入 `REJECTED_TOO_MANY_TOPICS`。
7. 对 1~3 个子问题，用同一个树 revision 和同一个启用叶子列表在专用线程池并行评分。
8. 对返回值做结构、节点身份、分数和数量复核；再按“每个有命中的子问题先保底一个，余量全局竞争”封顶。
9. 单子问题进入歧义判定；命中时保存最多 3 个选项和固定引导消息，终态为 `WAITING_CLARIFICATION`。
10. 否则生成 `KB_TARGETED`、`GLOBAL_FALLBACK`、`SYSTEM` 或 `NO_MATCH` 动作并写 `ACTIONS_READY`。

`POST /turns` 第一版等待稳定终态再返回。网络断开不会取消已经可靠保存的同步处理；客户端用相同
`clientRequestId` 重试只读取原本结果，不重复收费。3C 引入可取消任务后再替换传输形态，不在 3A 伪造取消。

## 5. 事务与并发边界

### 5.1 短事务

| 事务 | 原子内容 | 事务外内容 |
|---|---|---|
| 提交本轮 | 对话（可选）、用户消息、turn | 所有 Chat 调用 |
| 安装摘要 | 新摘要版本、旧摘要逻辑删除、水位线 CAS | 摘要生成 |
| 完成本轮 | 子问题、匹配、动作/引导、阶段记录、turn 终态 | 已完成的模型调用 |
| 修改意图树 | 锁树头、校验 expected revision、写完整新 revision、推进 current pointer | 页面预览与模型评分 |

事务失败不能被显示为成功。模型成功但终态事务失败时，本轮返回 `PERSISTENCE_FAILED`；不得只返回临时
模型结果，因为刷新后无法重现。

### 5.2 单活动本轮与幂等

- PostgreSQL 部分唯一索引保证一个对话只有一个 `SAVED/PROCESSING` 本轮。
- `clientRequestId` 是客户端为一次提交生成的 UUID；同一对话内唯一。
- 相同 key + 相同正文返回原 turn；相同 key + 不同正文返回 `409 IDEMPOTENCY_KEY_REUSED`。
- 不依赖 JVM 锁保证业务唯一性；JVM 锁只能优化同进程竞争。

### 5.3 有界并行

`IntentScoringExecutor` 固定最多 3 个工作线程、队列容量 3，并通过 Spring 配置给出显式线程名前缀。
一次本轮最多提交 3 个任务；拒绝策略是调用线程执行，使请求变慢但不丢子问题。每个 lambda 内部捕获
模型/解析失败并产出该子问题的空候选，`InterruptedException` 恢复中断标志并让本轮失败。

不用 `parallelStream`、公共 `ForkJoinPool` 或 Reactor scheduler。3B 若增加检索并行，必须使用独立执行器，
不能与意图评分共享队列。

## 6. 记忆与摘要

### 6.1 历史快照

上下文按以下固定次序组装：

1. 当前有效摘要（若有）；
2. 水位线之后、当前用户消息之前的最近 8 个完整轮次；
3. 当前用户消息。

“完整轮次”只包括稳定终态，且用户消息之后已有系统引导/助手消息或处理终态。未解决的
`WAITING_CLARIFICATION` 引导及候选必须保留在原始窗口，用户完成选择前不能进入摘要。

### 6.2 触发与安装

- `history-keep-turns=8`，`summary-start-turns=12`，启动校验 `12 > 8`。
- 在处理新本轮时，只统计此前完整且未被水位线覆盖的轮次。
- 达到 12 后，把“水位线之后、最近 8 轮之前”的完整轮次与旧摘要合并生成新摘要。
- 新水位线指向本次被压缩的最后一条消息；最近 8 轮不进入摘要。
- 摘要失败、超时或 CAS 冲突均保留旧摘要并继续本轮；阶段记录只保存失败类型，不保存输入输出。
- 摘要正文只保留目标、约束、偏好、指代对象和当时状态；Prompt 明确禁止把历史回答当事实结论固化。

## 7. 改写与结构化输出

### 7.1 最小归一化

第一版只做确定性文本处理：Unicode NFKC、统一空白、去首尾空白；不建设术语规则后台或缓存。这样即使
模型不可用也有稳定输入，同时避免在 3A 偷渡第三个管理界面。

### 7.2 改写契约

模型输出：

```json
{"rewrittenQuestion":"…","subQuestions":["…"]}
```

解析依次做：去单层 Markdown code fence、取 JSON object、字段类型校验、逐项 trim/去空、保持原顺序、
去完全重复。`rewrittenQuestion` 为空、JSON 非法或调用失败时，返回一个仅含最小归一化原问题的降级结果。

若合法响应包含 4 个或更多非重复子问题，不能 `limit(3)`；本轮进入 `REJECTED_TOO_MANY_TOPICS`。空数组则
用 `rewrittenQuestion` 作为唯一子问题。对比型是否拆分首先由 Prompt 约束，代码不声称能证明语义等价。

## 8. 意图树 revision 与缓存

### 8.1 快照模型

- 系统虚拟根 ID 固定为 `00000000-0000-0000-0000-000000000001`，不落节点表、不可编辑或删除。
- 虚拟根之下深度为 1~3；稳定 `nodeId` 在 revision 间不变。
- `GROUP` 只组织，不评分；`KB` / `SYSTEM` 不得有子节点，是仅有的评分叶子类型。
- 每个 revision 保存一份完整节点快照。写入时以 `expectedRevision` 锁定树头，构造和验证整棵新树后一次提交。
- 删除在新 revision 中标 `deleted=true`；旧 revision 不修改，因此历史 turn 始终能解释当时路径。

### 8.2 约束

- 同父节点有效名称唯一；有效节点不得孤立、成环或超过三层。
- `KB` 必须绑定存在、未删除且可用的知识库；`SYSTEM` 必须绑定闭集 action。
- 启用 `KB/SYSTEM` 必须具备名称、description、至少一个 example、有效目标和全部启用祖先。
- 停用/删除 GROUP 必须作用于整个子树；启用 GROUP 可选择仅当前或整个配置完整子树。
- 新建节点默认停用；普通字段保存与启停/删除使用不同命令。

### 8.3 本地缓存

单实例下使用 `AtomicReference<IntentTreeSnapshot>` 保存当前 revision。成功提交新 revision 后用数据库返回的
完整快照原子替换；读取若为空则从库加载。若缓存替换异常，下次请求按树头 revision 重载，不能继续把旧缓存
标成最新。缓存不是事实来源，不引入 Redis 或 TTL。

请求开始时捕获一个 Java 不可变对象，后续所有子问题共享它；即使缓存原子指针已更新，在途请求仍持有旧对象。

## 9. 意图评分、封顶与动作

### 9.1 Prompt 和预算

每个子问题把该 revision 的全部启用 `KB/SYSTEM` 叶子按 `nodeId` 排序后编码为 JSON。字段只含
`id/type/fullPath/description/examples`，不含知识库正文、`promptSnippet` 或已删除节点。

模板渲染完成后以 UTF-8 编码计算字节数；默认 `max-intent-prompt-bytes=65536`。超限时整个本轮显式失败为
`INTENT_CATALOG_PROMPT_TOO_LARGE`，不能随机截节点、按分支抽样或拆成多次评分，因为这些做法会改变分数可比性。

### 9.2 返回校验和数量

模型返回 `[{"id":"uuid","score":0.0}]`。解析后：

- 去 code fence 并要求顶层数组；非法结构降级为空候选。
- 丢弃未知/非启用/非叶子 ID、非有限数值和 `[0,1]` 外分数。
- 同一节点重复时取最高合法分；分数降序、`nodeId` 升序保证稳定。
- `score < retainThreshold(0.35)` 丢弃；单子问题最多保留 3 个。
- 全局 `maxTotalIntents=3`，且必须 `>= maxSubQuestions=3`。
- 先按子问题顺序给每个有候选者一个最高分名额，剩余额度按分数和稳定 tie-break 竞争。

### 9.3 歧义

只对“一个子问题、至少两个跨品类 KB 候选、最高分至少 0.6、用户没有明确选择线索”判定。两层 KB 叶子的
品类是自身；三层 KB 叶子的品类是它的直接 GROUP 父节点。

设 `gap = topScore - secondCategoryScore`，统一配置 `ambiguityMargin=0.15`：

- `gap <= 0.15`：明确歧义，直接引导；
- `0.15 < gap <= 0.30`：灰区，调用一次 `BALANCED` Chat 复核；失败保守引导；
- `gap > 0.30`：不歧义。

候选都低于 0.6 时不生成虚假精确选项，走无匹配/全局兜底动作。引导最多取三个品类代表，文案由代码模板
生成，不让模型自由编写；选择结果在下一轮以结构化 `clarification` 引用前 turn 和 nodeId。

### 9.4 动作映射

阈值集中在 `QaUnderstandingProperties.Thresholds` 并校验：

```text
retain 0.35 <= targeted 0.40 <= fallback 0.60 <= supplement 0.80
ambiguityMargin = 0.15, 0 < margin < 0.5
```

对每个子问题：

- `SYSTEM`：只有全部子问题都只有同一 SYSTEM 主候选时形成 `SYSTEM` 动作；3A 不生成回复。
- `KB_TARGETED`：每个分数 `>=0.4` 的 KB 候选形成一个定向知识库动作。
- `GLOBAL_FALLBACK`：无候选、最高分 `<0.6`，或只有一个 KB 候选且分数 `<0.8` 时补充。
- `NO_MATCH`：评分调用/解析失败且没有可信候选；保存原因，前端提示补充方向。
- `WAITING_CLARIFICATION`：歧义短路，不同时产生 KB/SYSTEM 动作。

3A 只记录声明，不调用知识库。3B 必须消费这些动作而非重新解释分数。

## 10. Prompt 与模型调用

资源固定在：

```text
backend/src/main/resources/prompts/qa/3a/
├── summary-system.md
├── summary-user.md
├── rewrite-system.md
├── rewrite-user.md
├── intent-score-system.md
├── intent-score-user.md
├── ambiguity-confirm-system.md
└── ambiguity-confirm-user.md
```

`PromptTemplateCatalog` 启动时一次加载，校验必需占位符恰好存在、未知占位符为零、单模板 UTF-8 大小和总预算
为正。渲染器只做命名占位符替换，不执行脚本、SpEL 或任意文件 include。模板修改属于代码变更，随发布重启；
没有数据库 Prompt、文件 watcher 或管理 API。

所有调用构造 `ChatCommand(messages, tools=[], BALANCED, CallOptions.defaults())`。采样由 ADR 0006 的 catalog
profile 注入 Provider 请求。3A 不在业务命令里传 `temperature/topP`。

## 11. API 与传输边界

3A 使用 JSON over HTTP；没有 SSE。`POST /api/qa/turns` 返回稳定终态及 Inspector 数据，超时由模型能力端口
和 MVC 请求预算共同约束。具体 DTO、状态码、游标和错误码见 [API 契约](api.md)。

Controller 只暴露：

- 对话列表/详情/消息、重命名、逻辑删除；
- 幂等本轮提交和本轮详情；
- 意图树当前 revision、变更影响预览、节点创建/编辑/启停/删除；
- 可绑定知识库的只读选择列表。

接口不暴露 Prompt、模型原始 JSON、评分 reason、Provider 错误体、`ExecutionInfo` 或采样参数。

## 12. 隐私、诊断与 ID 所有权

| ID | 所有者 | 粒度 | 是否可含正文的业务表 |
|---|---|---|---|
| `conversationId` | `com.app.qa` | 多轮对话 | 是 |
| `turnId` | `com.app.qa` | 一次用户提交到一个稳定终态 | 是 |
| `messageId` | `com.app.qa` | 一条用户/助手/引导消息 | 是 |
| `taskId` | 3C `com.app.qa` | 一次可排队、可取消生成任务 | 3A 不创建 |
| `logicalCallId` | `com.app.ai` | 一次模型能力调用 | 否，只关联 |
| `RequestId` | `com.app.ai` | 调度请求关联 | 否，不作为业务主键 |

`qa_message`、`qa_summary` 和 `qa_subquestion` 是受逻辑删除约束的业务数据，可以保存用户可见正文。
`qa_turn_stage` 只保存阶段、时间、结果、稳定原因码、revision 和 `logicalCallId`；不得保存问题、摘要、Prompt、
模型输出、意图 description/examples 或供应商错误体。`ai_*` 继续遵守 ADR 0005 的无正文诊断边界。

日志使用 ID 和枚举，不把异常对象直接拼接到带外部响应体的日志。页面 Inspector 展示持久化业务结果，不读取
`ai_*` 私有诊断表拼“思维链”。

## 13. 配置与启动校验

建议配置形态：

```yaml
app:
  qa:
    understanding:
      max-sub-questions: 3
      max-total-intents: 3
      history-keep-turns: 8
      summary-start-turns: 12
      max-intent-prompt-bytes: 65536
      processing-stale-after: 3m
      thresholds:
        retain: 0.35
        targeted: 0.40
        fallback: 0.60
        supplement: 0.80
        ambiguity-margin: 0.15
```

启动失败条件：数值非有限或越界；阈值偏序不成立；`summary-start-turns <= history-keep-turns`；
`max-total-intents < max-sub-questions`；子问题上限不是 3；Prompt 不存在/占位符错误；`BALANCED` Chat 没有
采样 profile；配置的系统 action 不在闭集。

阈值和 Prompt 都重启生效。意图节点是业务配置，通过 revision 在线生效；两者不能混用同一热更新机制。

## 14. 失败语义

| 失败点 | 领域结果 | 是否继续 |
|---|---|---:|
| 用户消息持久化失败 | HTTP 失败，无 turn | 否 |
| 摘要加载/生成/安装失败 | 旧摘要 + 近期历史，记录降级 | 是 |
| 改写调用/解析失败 | 最小归一化问题，记录降级 | 是 |
| 合法子问题超过 3 | `REJECTED_TOO_MANY_TOPICS` | 否 |
| 单个意图评分失败 | 该子问题空候选 | 其他子问题继续 |
| Prompt 超预算 | `FAILED/INTENT_CATALOG_PROMPT_TOO_LARGE` | 否，不截节点 |
| 全部候选低分 | `GLOBAL_FALLBACK` / `NO_MATCH` | 3A 完成 |
| 灰区复核失败 | `WAITING_CLARIFICATION` | 等用户 |
| 终态持久化失败 | `PERSISTENCE_FAILED` | 否，不返回临时成功 |
| 阶段诊断写入失败 | 无领域结果，只记警告 | 是，业务终态完全不受影响 |
| 意图 revision 冲突 | `409 INTENT_TREE_REVISION_CONFLICT` | 管理操作失败且不部分应用 |

阶段诊断是 best-effort，这条保证落在端口上（`TurnStageSink.recordBestEffort`）而不是各个调用点：四个编排
组件都要写 stage，漏掉任何一个的后果都一样——一次 stage 写失败会被理解编排的 catch 当成理解失败，把一个
已经算完的 turn 判成 `FAILED`，等于让诊断反过来决定业务结果。

## 15. 3B/3C 扩展边界

> 3B 已按本节边界展开，落在 §18~§22。本节保留为 3A 当时冻结的承诺，用来核对 3B 有没有走偏。

- 3B 读取 `ACTIONS_READY` 的不可变动作和意图 revision；不得回读“当前树”重算旧 turn。
- 3B 新增检索/工具结果表时引用 `turnId + subQuestionId + actionId`，不把结果塞回意图快照表。
- 3C 新增 `taskId`、SSE 和唯一终态；`turnId` 仍是业务轮次，不能与可重试/可排队任务混为一谈。
- 许可、取消 token 和等待队列属于单 JVM 易失运行态，不进入 PostgreSQL 轮询；最终任务状态才持久化。
- 3C 的不可信内容隔离必须把用户问题、chunk、工具返回和 `promptSnippet` 分开编码；3A 保存 snippet 不执行。

## 16. 取舍与已知限制

- 整树快照写放大换取了清晰的一致读、历史解释和简单回滚材料。第一版树规模受 64 KiB 评分 Prompt 预算天然
  约束；若未来树显著扩大，应先评测分层路由，再决定增量快照，不能先把评分拆批后假装分数仍可比。
- 同步请求让 3A 状态简单，但长模型延迟会占用 Servlet 工作线程。最大并发仍由部署和 `com.app.ai` 预算限制；
  3C 的排队/流式到期后再引入异步任务，不在 3A 同时维护两套传输模型。
- 本地缓存只适用于当前单实例。多实例升级需要 revision 通知或每请求轻量校验；不是把取消/缓存统一改成数据库轮询。
- 3A 的阈值是可运行基线，不是质量结论；Phase 4 必须用固定评测集再调参。

## 17. Validation

- 数据模型约束、并发 CAS、逻辑删除和迁移测试。
- Prompt golden test、UTF-8 边界、非法 JSON 和恶意节点内容测试。
- 可编程假 Chat 覆盖摘要、改写、并行评分、灰区确认及每次 `logicalCallId` 关联。
- API 契约测试覆盖幂等、游标、ProblemDetail、revision 冲突和 Inspector 数据。
- 浏览器按已确认的问答 A、意图树 C 完成 3A 状态走查。
- 完整场景和编号见 [测试矩阵](test-matrix.md)。

---

# 3B — 检索 + 工具（draft，2026-08-08）

> 本段是 Step C 草案，未经闸门确认前不写实现代码。产品语义冻结在
> [PRD §12](../../docs/prd/2026-08-07-qa-pipeline-prd.md)，界面表达冻结在
> [conversation ui-spec §13](conversation/ui-spec.md)，检索选型见
> [ADR 0008](../../docs/adr/0008-hybrid-retrieval-and-rrf-fusion.md)。
> 三者与本段冲突时，产品语义以 PRD 为准、界面以 ui-spec 为准、选型理由以 ADR 为准。

## 18. 3B 方案结论

`ACTIONS_READY` 之后追加一个**检索与工具阶段**，产出两类结果：**来源**（用户可核验的证据）与
**工具调用记录**。3B 仍不生成答案——它把"依据"备齐，交给 3C。

```text
ACTIONS_READY（3A 终态，不可变）
   │
   ├─ 每个子问题：按动作激活通道 → 有界并行检索 → RRF 融合 → 按 chunk_id 去重 → 截断 → Rerank
   │                                                                          └─ 三态：真实/Noop/故障
   ├─ 工具：原生 Function Calling 一次 Chat 调用 → 白名单与 Schema 复核 → 副作用工具等待用户确认
   │
   └─ RETRIEVED（3B 终态）：来源列表 + 工具记录 + 显式降级标记
```

三条不能松的边界：

1. **3B 消费动作，不重新解释分数。** 通道激活、定向范围、是否兜底全部来自 3A 的
   `qa_retrieval_action` 行。3B 不读意图分数，也不回读"当前树"。
2. **降级一路显式到 API。** 未精排、部分知识库失败、工具不可用都是结果对象上的字段，
   不是日志，也不靠条数倒推。
3. **不可信内容不进调度诊断。** chunk 正文、工具参数值与工具返回一律不写 `com.app.ai` 的诊断表
   （ADR 0005）；问答侧自己的表存什么见[数据模型](data-model.md) §13~§15。

## 19. 检索编排

### 19.1 模块与依赖

```text
com.app.qa
├── retrieval          # 通道接口与三个实现、RRF 融合、去重截断、精排调用
│   ├── channel        # SearchChannel、IntentTargetedChannel、GlobalVectorChannel、KeywordChannel
│   └── fusion         # ReciprocalRankFusion、Deduplicator、TopKTruncator
├── tool               # 工具白名单、Schema 复核、调用循环、确认状态机
└── service            # RetrievalOrchestrator（在 QuestionUnderstandingService 之后串接）

com.app.qa.retrieval ──> com.app.ai.model.RerankClient
com.app.qa.retrieval ──> com.app.knowledge.repository.ChunkSearchPort（窄端口，只读）
com.app.qa.tool      ──> com.app.ai.model.ChatClient
```

新增的窄端口 `ChunkSearchPort` 由知识库模块提供，暴露三个查询：定向向量、全局向量、关键词。
**问答模块不拼 chunk 表的 SQL**，理由与 3A 的 `KnowledgeBaseLookup` 相同——跨模块直接写别人的
表结构，等于把两个模块的迁移绑死。

### 19.2 通道

| 通道 | `priority` | 激活条件 | 作用域 | 排序依据 |
|---|:---:|---|---|---|
| `INTENT_TARGETED` | 1 | 该子问题有 `KB_TARGETED` 动作 | 动作里的 `knowledge_base_id` 集合 | 余弦距离 |
| `KEYWORD` | 2 | 始终 | 与同轮向量通道一致 | `ts_rank_cd` |
| `GLOBAL_VECTOR` | 3 | 该子问题有 `GLOBAL_FALLBACK` 动作 | 全库 | 余弦距离 |

- **两个向量通道不同时激活**（ADR 0008 决策 2）。在 pgvector 单表下定向结果是全局的子集，
  同时激活等于查两遍同一份数据。
- `priority` **只有一套**，同时用于稳定排序与去重取舍。不设第二套按通道类型的优先级。
- 检索用的是**改写后的问题**（3A `rewrite.rewrittenQuestion`），不是用户原话。

### 19.3 并行与连接池硬约束

每个检索任务独占一条只读事务连接（`ChunkVectorRepository.search` 需要
`set local hnsw.iterative_scan = strict_order`）。因此：

```text
qa.retrieval.max-parallelism  <  spring.datasource.hikari.maximum-pool-size
```

- 池大小从"未配置默认 10"改为**显式声明 10**——显式化是为了校验与可见性，不是为了调大。
  取更大的值会被测试环境放大：每个 Spring 测试上下文各建一个池，Postgres 默认
  `max_connections=100`，实测 20 就会打穿（工单 01 期间验证）。要调大必须同时抬高
  `max_connections`。
- 检索并行度基线 4，粒度是 **(子问题 × 通道)** 任务。
- 启动时校验上式，不满足**直接失败**并打印两个实际值。理由与 Embedding 维度冲突同源：
  放行只会把问题推迟成运行期的"检索变慢了"，而那个症状会把排查带到线程池而不是连接池。
- 3C 的全局并发许可要与这里一起核算：`许可数 × 并行度` 也必须留在池内。这条写进 3C Step C 的输入。

### 19.4 融合、去重与截断

顺序固定，且**不可并行**（后一步依赖前一步）：

```text
各通道结果 → RRF 融合 → 按 chunk_id 去重 → 截断到 rerankInputSize → Rerank → 截断到 topN
```

- **RRF**：`score(c) = Σ_i 1/(60 + rank_i(c))`，`rank` 从 1 起。只吃排名，不吃分数。
- **去重**：键是 `chunk_id`。同一 chunk 的 RRF 分数累加；来源归属取 `priority` 最小的通道。
  **不写 `text.hashCode()` 兜底路径**——chunk 一定有主键，兜底只会带来静默丢证据的可能。
- **两次截断不同性质**：第一次是喂给 Rerank 的输入上限（受 `RerankProfile.maxDocuments` 约束），
  第二次是最终来源条数。
- **去重是必需清洗，精排是可选增强**：去重失败 = 本轮检索失败；精排失败 = 降级继续。
  不用同一个 try-catch 一视同仁地跳过。

### 19.5 精排三态

调用 `RerankClient`，把 `RerankResult` 的三态**原样**传导，不在问答侧重新发明：

| 情形 | `rerankApplied` | 界面表现 |
|---|:---:|---|
| 真实精排 | `true` | 不加标记（默认状态不渲染成成就） |
| Noop 降级（无 Key / 供应商故障 / 目录未配） | `false` + `degradationReason` | `未精排` + 一句解释 |
| 调用抛错 | 视为降级 | 同上；成因只进处理详情与链路记录 |

- **降级时 `relevanceScore` 必为 null**，这是端口的构造期不变量，问答侧不得回填假分数。
- **返回不足 topN 不补齐**（PRD §12.5）。`未精排 · 3 条` 是完全正常的一屏。
- 用稳定 ID 对齐，不用下标（`RerankCommand` 的既有纪律）。

### 19.6 失败边界

| 失败点 | 行为 | 用户可见 |
|---|---|---|
| 单个知识库目标检索失败 | 该目标标 `FAILED` + 归一化原因，其余照常 | 点名该库 + `结果不完整`，可重试 |
| 全部目标失败 | 本轮标 `RETRIEVAL_FAILED` | 与"无结果"文案不同：一个没查成、一个查了没有 |
| 关键词通道失败 | 该通道结果为空，向量通道照常 | 不单独提示（通道构成不对用户暴露） |
| 融合/去重失败 | 本轮检索失败 | 同"全部目标失败" |
| 精排失败 | 降级为未精排 | `未精排` |

**部分失败必须是显式状态**，不得用 `results.length < expected` 倒推（PRD §12.4）。

### 19.7 重试失败的知识库

ui-spec §13.6 承诺"重试只重跑失败的那部分，不重走改写与意图"。实现上要求：

- 检索结果按 `(turnId, subQuestionId, actionId)` 落库，重试是针对失败 action 的**幂等重放**；
- 重放不新建 turn、不重新评分、不重问歧义；
- 重放结果合并进同一 turn 的来源集合，`partial` 标记随之重算。

## 20. 关键词通道

### 20.1 派生表与写入点

新建 `document_chunk_keyword`，形态与 `document_chunk_embedding` **完全对齐**（见
[数据模型 §13](data-model.md)）：`chunk_id` 主键、物理删除、每行都对应一个未删除且启用的分块、
所属文档也未删除且启用。

写入点与向量写入是同一处（知识库入库链路）。**两张派生表必须同增同删**——任何一处漏写都会让
关键词通道静默少召回，而这种缺失没有任何报错。契约测试要正面断言两张表在同一批分块上行集合一致。

### 20.2 分词一致性

索引期与查询期必须用**同一份切词代码和同一份词典**：

```text
入库： content ──切词──> "退货 政策 材料" ──> to_tsvector('simple', ...)
查询： 改写后问题 ──同一套切词──> "退货 材料" ──> to_tsquery('simple', '退货 & 材料')
```

- 用 `'simple'` 配置：文本进库前已经切好词，不需要 Postgres 再做语言处理。
- **换词典必须重建整列**。这条要进部署文档，不能只活在代码注释里——词典换了却不重建，
  表现是关键词通道召回率悄悄下降，没有任何报错。
- 切词器版本与词典摘要写进启动日志与配置指纹，便于事后判断某次召回异常是不是换词典造成的。

### 20.3 存量回填

应用侧分词决定了回填不能是纯 SQL 迁移。V9 只建表建索引，回填由一个**带 `enabled` 开关的批处理
组件**完成（跨模块纪律：调度类组件都要有开关，ADR 0002 的单实例假设）：

- 按 `chunk_id` 游标分批，只处理 `document_chunk_embedding` 有而 `document_chunk_keyword` 没有的行；
- 幂等，可重复执行；
- 未回填完成时关键词通道**照常工作**，只是召回不全——这属于已知降级，不是故障。

## 21. 工具调用

### 21.1 路线与边界

采用**原生 Function Calling**（计划 D-03）：一次 Chat 调用同时完成"选哪个工具"与"参数是什么"，
不再单独调一次 LLM 提参。可用工具来自**白名单配置**，不来自意图树——意图树不承担工具绑定，
裁决与理由见 [conversation ui-spec §13.9](conversation/ui-spec.md)。

接受的代价（D-03 已确认）：工具选择由模型决定，不如意图树可审计。补偿是把"模型请求了哪个工具、
带了什么参数、结果如何"完整暴露到界面与链路记录。

### 21.2 白名单与工具定义

```yaml
qa:
  tools:
    - name: submit-return-ticket
      display-name: 提交退货工单
      description: 在售后工单系统中新建一张退货工单，并把工单号回写到本次对话。
      side-effect: true
      timeout: 10s
      input-schema: classpath:tools/submit-return-ticket.schema.json
```

- `description` 同时是**给模型的说明**与**给用户看的那句话**（ui-spec §13.7.2）。它来自配置，
  **不能由模型生成**——否则模型既决定调什么工具，又决定怎么向用户描述它，确认就成了走过场。
- `side-effect` 决定是否需要二次确认，是产品语义不是实现细节（PRD §12.7）。
- 重复 `name` 视为配置错误，**启动失败**。理由与 Embedding 维度冲突同源；课程实现里"后注册的静默
  覆盖、顺序取决于配置文件排列"是一个改一次配置顺序就能悄悄换掉后端的耦合。
- 工具清单为空时不向模型发送 `tools` 字段，也不产生"工具不可用"提示——没有配置工具与配置了但不可用
  是两回事。

### 21.3 代码侧 Schema 复核（安全边界，不是防御性编程）

`ToolCall.argumentsJson` 只经过"是合法 JSON"的校验，Provider 层不做任何 Schema 校验。因此在执行
之前必须由代码复核，逐条对应 PRD §12.7：

1. **字段名白名单**：遍历 Schema 声明的参数名，不遍历模型返回的 key。模型编造的字段直接丢弃。
2. **类型校验**：Schema 声明 `integer` 而模型给 `10.5` 或字符串，拒绝。
3. **枚举与取值校验**：字段名对但取值不在 `enum` 内同样拒绝——只校验字段名是"把约束告诉了模型
   就当模型一定会遵守"。
4. **必填校验**：缺任一必填参数**不得调用工具**，不存在"参数被过滤掉后照常执行"的路径。
5. **禁止默认值兜底**：抽取失败即中止并请用户补充，绝不用默认值继续执行。

任何一条不过 → 状态 `ABORTED`，界面点名缺什么或哪个值非法（给允许值，不给 Schema 原文）。

### 21.4 状态机与副作用确认

```text
                       ┌─ side-effect=false ─→ RUNNING ─→ SUCCEEDED / FAILED
REQUESTED ─ 复核通过 ─┤
    │                  └─ side-effect=true ──→ PENDING_CONFIRM ─┬─ 用户确认 → RUNNING → …
    │                                                            └─ 用户取消 → CANCELLED
    ├─ 复核不通过 ────────────────────────────→ ABORTED
    └─ 不在白名单 ────────────────────────────→ UNAVAILABLE
```

- **`PENDING_CONFIRM` 期间不发起任何外部调用。** "确认前一律不执行"是产品语义。
- **确认不可复用**：失败后重试有副作用的工具要重新回到 `PENDING_CONFIRM`。
- 确认状态持久化（见[数据模型 §15](data-model.md)），刷新页面后仍在等待，不会因为前端状态丢失
  而变成"永远等不到确认"或"静默执行了"。
- 3B **无中止能力**（停止属 3C）；`RUNNING` 的超时由工具配置的 deadline 决定，超时归一化为 `执行超时`。

### 21.5 结果与失败文案

- 工具返回是**不可信内容**：落库与传输都按纯文本，界面不解析 HTML/Markdown（ui-spec §13.7.3）。
  3C 把它拼进 Prompt 时还要再做一次标签转义，那属于 D-08 的不可信内容隔离。
- 失败只暴露**归一化文案**，绝不透传异常 message——它可能带内部主机名、端口和堆栈片段。
  归一化闭集与 3A 的 `toQaUserMessage` 同源，新增码值在 [API 契约](api.md) 冻结。

## 22. 3B 的配置、诊断与启动校验

### 22.1 配置

| 键 | 基线 | 校验 |
|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | 10 | 显式声明；调大必须与 Postgres `max_connections` 一起改 |
| `qa.retrieval.max-parallelism` | 4 | `< maximum-pool-size`，否则启动失败 |
| `qa.retrieval.channel-top-k` | 50 | `>= topN` |
| `qa.retrieval.rerank-input-size` | 30 | `<= RerankProfile.maxDocuments` |
| `qa.retrieval.top-n` | 8 | `>0`；上限受 3C Prompt 预算倒推，Step C 只定基线 |
| `qa.retrieval.rrf-k` | 60 | `>0` |
| `qa.tools.*` | 见 §21.2 | `name` 唯一；Schema 可解析；有副作用工具必须有 `description` |

`top-n` 的最终值应由 3C 的上下文预算倒推，而不是先拍一个数再看塞不塞得下。3B 先给可运行基线，
在 3C Step C 复核。

### 22.2 诊断边界

- `com.app.ai` 的诊断表**只记模型调用**（Rerank、工具所在的 Chat 调用），不记 chunk 正文、
  工具参数值与工具返回。ADR 0005 的分层不因 3B 松动。
- 问答侧的检索漏斗（各通道命中数 → 去重后 → 精排后）落 `qa_turn_stage` 的**计数字段**，不落正文。
  这正是课程实现里只存在于 `log.info` 的那份数据，落成结构化记录才能被查询和告警。
- 漏斗与降级成因只出现在"处理详情"（验收信息），不进用户可见的来源区（ui-spec §13.3）。

### 22.3 与并行会话的接口

3B 依赖 DashScope 原生 Rerank adapter 落地（Phase 2 工单 23），但**不重复实现它**——
X-1 核对结论与该依赖的处理见[计划 §8](../../docs/exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)。
在它提交并闭环之前，3B 的 Step D 只能演示 Noop 降级路径；这不阻塞 Step C，也不阻塞除
"真实精排演示"以外的任何验收项。
