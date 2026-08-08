# 技术方案：AI 知识问答核心链路（Phase 3A）

- `status`: approved
- `approved_at`: 2026-08-07
- `date`: 2026-08-07
- `scope`: 3A — 记忆 + 改写 + 意图；3B/3C 只冻结扩展边界
- `prd`: [Phase 3 PRD](../../docs/prd/2026-08-07-qa-pipeline-prd.md)
- `phase_plan`: [Phase 3 执行计划](../../docs/exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)
- `data_model`: [数据模型](data-model.md)
- `api`: [API 契约](api.md)
- `tests`: [测试矩阵](test-matrix.md)
- `related`: [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)、[ADR 0003](../../docs/adr/0003-separate-model-capability-ports.md)、[ADR 0005](../../docs/adr/0005-separate-model-runtime-state.md)、[ADR 0006](../../docs/adr/0006-bind-chat-sampling-to-service-tier.md)、[ADR 0007](../../docs/adr/0007-versioned-intent-tree.md)

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
