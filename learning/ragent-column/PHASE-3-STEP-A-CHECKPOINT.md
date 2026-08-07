# Phase 3 Step A 学习检查点（AI 知识问答核心链路）

状态：**待确认**。18 篇学习整理、§5 的现状对照与设计原则均已完成，**内容上 Step A 已具备闸门条件**；
剩余的唯一动作是项目所有者对 §3 的 D-01~D-08 逐条裁决并签字（`confirmed_at` 仍为空）。
裁决完成后按主计划创建 Phase 3 执行计划，此后的 PRD、技术设计与交付状态不再回写本检查点。

- `phase`: Phase 3 / AI 知识问答核心链路 / Step A
- `owner`: liuqiang
- `started`: 2026-08-07
- `confirmed_at`: —
- `source_group`: [05-qa-pipeline](INDEX.md)（18 篇）
- `exec_plan`: 待 Step A 完成后创建

## 1. 学习进度

| 篇 | 文件 | 状态 |
|---|---|---|
| 1 | [八阶段全景](05-qa-pipeline/05-01-知识问答八阶段全景.md) | done |
| 2 | [会话记忆的存储与加载](05-qa-pipeline/05-02-会话记忆的存储与加载.md) | done |
| 3 | [摘要压缩与水位线](05-qa-pipeline/05-03-摘要压缩与水位线.md) | done |
| 4 | [查询改写与子问题拆分](05-qa-pipeline/05-04-查询改写与子问题拆分.md) | done |
| 5 | [意图树的设计与存储](05-qa-pipeline/05-05-意图树的设计与存储.md) | done |
| 6 | [意图打分的 Prompt 与 JSON 容错](05-qa-pipeline/05-06-意图打分的Prompt与JSON容错.md) | done |
| 7 | [意图并行调度与总量封顶](05-qa-pipeline/05-07-意图并行调度与总量封顶.md) | done |
| 8 | [歧义检测与引导式问答](05-qa-pipeline/05-08-歧义检测与引导式问答.md) | done |
| 9 | [意图到检索动作的映射](05-qa-pipeline/05-09-意图到检索动作的映射.md) | done |
| 10 | [多通道并行检索架构](05-qa-pipeline/05-10-多通道并行检索架构.md) | done |
| 11 | [检索结果后处理流水线](05-qa-pipeline/05-11-检索结果后处理流水线.md) | done |
| 12 | [MCP 工具调用链路](05-qa-pipeline/05-12-MCP工具调用链路.md) | done |
| 13 | [工具参数的 LLM 抽取](05-qa-pipeline/05-13-工具参数的LLM抽取.md) | done |
| 14 | [Prompt 组装与消息数组](05-qa-pipeline/05-14-Prompt组装与消息数组.md) | done |
| 15 | [流式生成的五层链路](05-qa-pipeline/05-15-流式生成的五层链路.md) | done |
| 16 | [停止生成与跨集群取消](05-qa-pipeline/05-16-停止生成与跨集群取消.md) | done |
| 17 | [排队限流的入队与抢占](05-qa-pipeline/05-17-排队限流的入队与抢占.md) | done |
| 18 | [异步等待与超时拒绝](05-qa-pipeline/05-18-异步等待与超时拒绝.md) | done |

> 专栏原文抓取路径（可复现）：短链 `t.zsxq.com/xxx` → topic 页只有摘要 → 取 `a.link-of-topic` 的 href →
> `articles.zsxq.com/id_xxx.html` 才是正文。topic 页上的 `p.showAll`「展开全部」是 `display:none`
> 的死元素，点击无效。原文暂存于本机 `~/.xrag-column-raw/`（项目外，不进仓库）。

## 2. 已确认的决策

- **`2026-08-07`：本期要做意图树管理界面。** 由项目所有者确认。影响：Step B 的界面产出物翻倍——
  问答对话页与意图树管理页**各需要一份 `ui-spec.md` 与一个可点击原型**，且两者都必须在写任何前端
  代码之前经所有者确认（[主计划 §6.1](../../docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md) 的产出物闸门）。
  意图树管理页不是简单表单页，至少要覆盖：树形增删改、`description` 编辑（它直接决定 LLM 匹配
  准确率）、节点启停、以及"父节点停用/删除时子节点必须一并处理"的一致性校验失败提示。

- **`2026-08-07`：叶子节点绑定 xrag 知识库 ID，而非 Milvus Collection。** 这不是可选项，是既定
  事实的推论——pgvector 在 Phase 0 已锁定为向量存储方案。由此带来的实现差异（原文是并行查多个
  物理集合，xrag 是同一张向量表按知识库 ID 过滤后并行查）属于 Step C 技术方案的范畴，
  见下方待决策项。

## 3. 待决策问题（Step A 完成时提交所有者裁决）

| # | 问题 | 现状 | 备注 |
|---|---|---|---|
| D-01 | Phase 3 是否拆成 3A/3B/3C 三个子里程碑 | 待定 | 18 篇是迄今最大一组（Phase 1 为 11 篇/20 工单，Phase 2 为 8 篇/23 工单）。计划已确认"先走 Step A，切分留到本检查点裁决" |
| D-02 | 意图树、多通道检索、结果融合的选型 | 待定 | **检索策略尚无 ADR**（见 CLAUDE.md），不要假设已有定论。意图树是最该形成 ADR 的一项 |
| D-03 | MCP 工具调用是否纳入本期 | 待定 | 对应第 12~13 篇 |
| D-04 | 对话历史与并发许可的存储形态 | 待定 | Postgres / Redis / 进程内。受 [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md) 单实例假设约束 |
| D-05 | Chat 供应商选型 | 待定 | 目前 catalog 里 Chat 只有阿里云百炼 `qwen-plus`。若改用其他供应商需在 Step C 之前定——服务等级设计（`ECONOMY` 做改写/打分、`QUALITY` 做生成）要按实际可得的模型来 |
| D-06 | 采样参数（temperature / topP / thinking）进 `CallOptions` 还是绑 `ServiceTier` | 待定 | **端口设计空白**，非实现遗漏：`com.app.ai` 全包 grep 这三个词零命中，[ADR 0003](../../docs/adr/0003-separate-model-capability-ports.md) 未讨论过。绑 tier 更符合"业务代码不触碰 Provider 细节"，但要求 tier 语义从"选哪个模型"扩到"怎么调"。见 [第 14 篇](05-qa-pipeline/05-14-Prompt组装与消息数组.md) |
| D-07 | 深度思考（thinking）是否纳入本期 | 待定 | **是产品需求不是技术细节，应在 Step B 定**。`ChatStreamEvent` 是 sealed interface 且无 `ThinkingDelta`，`ChatCommand` 也无 thinking 开关——支持它要同时改端口入参与出参，属 ADR 0003 管辖。见 [第 15 篇](05-qa-pipeline/05-15-流式生成的五层链路.md) |
| D-08 | 不可信内容的隔离策略 | 待定 | 进入 Prompt 的不可信输入至少四类：用户问题、**检索 chunk（来自已上线的知识库上传通道）**、MCP 返回、运营写的 `promptSnippet`。原文只在一个 Prompt 里写了一句声明且无标签转义。Step C 应单开一节。见 [第 12](05-qa-pipeline/05-12-MCP工具调用链路.md)~[14 篇](05-qa-pipeline/05-14-Prompt组装与消息数组.md) |

## 4. 已识别的阻塞前提

> **`2026-08-07` 状态变更：Chat 凭据阻塞已解除，Rerank 阻塞原因被证伪并重新定性。**
> 以下记录本文件写入时实读到的事实；Chat 真实调用的验证结论由并行进行的 Provider 准入工作产出，
> 落点在 `backend/src/test/java/com/app/ai/RealChatApiTests.java` 与 `ai-models.yml` 的注释，
> **本检查点不复述其运行结果**。

**原阻塞（已解除）**：`DASHSCOPE_API_KEY` 未配置，`aliyun-qwen-plus-cn` 在路由中被判
`CONFIG_INCOMPLETE`，诊断表中 CHAT/STREAM_CHAT 零真实调用。现 `backend/.env` 已注入该变量，
`RealChatApiTests` 以 `@EnabledIfEnvironmentVariable(DASHSCOPE_API_KEY)` 守门，覆盖同步与流式两条
链路及诊断落库。

**由此暴露的两个新事实**，都直接影响 Phase 3：

1. **`RoutingChatClient` / `RoutingStreamingChatClient` 此前没有任何 `@Configuration` 把它们暴露成
   bean**（`ChatClientConfiguration` 是 `2026-08-07` 才补的）。根因是 Phase 2 没有 Chat 的业务消费者，
   接线缺失一直没被发现——**Phase 3 是第一个消费者，Step C 拆工单时不能假设"调度层已经可直接注入"。**
2. **Rerank 打不通的原因不是凭据而是适配器。** `OpenAiCompatibleRerankProvider` 打
   `{base}/reranks`，百炼 compatible-mode 下该端点 404；同一把 Key 打 DashScope 原生
   `/api/v1/services/rerank/text-rerank/text-rerank` 返回 200 与真实分数。因此
   `aliyun-qwen3-rerank-cn` 维持 `enabled: false`——**是"尚未通过准入"，不是"供应商不支持"**。
   需要一个 DashScope 原生 rerank 适配器，属新实现工作（见 §5.1 阶段 6c 的待决策）。

**剩余影响**：Step D 的答案生成不再受限于假 Provider；但**阶段 6c 的精排若确定要用 rerank，则被上述
适配器缺口阻塞**，该缺口归属（Phase 2 遗留工单 23 还是 Phase 3 新工单）需在 Step A 闸门一并裁决。

## 5. 与 xrag 现状的对照

### 5.1 逐阶段四段式对照

「xrag 当前实现」一列是 `2026-08-07` 实读代码的结论，不是推测。基线事实：Flyway 到 V6，
已建表只有知识库五张（`knowledge_base` / `source_document` / `document_chunk` /
`document_chunk_embedding` / `ingestion_run`）与调度诊断五张（`ai_logical_call` /
`ai_attempt` / `ai_route_candidate` / `ai_route_event` / `ai_breaker_transition`）——
**问答链路一张表都还没有**。

| 阶段（篇） | 课程方案 | xrag 当前实现 | 可复用部分 | 待决策问题 |
|---|---|---|---|---|
| **入口：限流与幂等**（17~18） | `@IdempotentSubmit` + Redis 五件套排队（可过期信号量 / ZSET / 自增 seq / entry 标记 / Pub/Sub）+ `PollNotifier` 三层防惊群 | 仅 `UploadConcurrencyFilter` 的本地 `Semaphore(n, true)`，只保护上传 | **排队而非拒绝的产品论证**；超时拒绝要写会话记忆的三方案对比；`META`/`REJECT`/`FINISH`/`DONE` 复用同一事件框架 | 单实例下用本地信号量已定，但**等待预算 / 模型 Deadline / SSE 超时三者的关系需一处派生或启动校验**（D-01 切分粒度相关） |
| **阶段 1 会话记忆**（2~3） | 摘要压缩 + 水位线，摘要作 system 消息插入 | 无对话/消息表，无任何实现 | 水位线机制、摘要与近期原始轮次分离 | D-04 存储形态（**许可已定为进程内，历史仍待定**）；水位线只压 history 不管证据体，预算口径要重定 |
| **阶段 2 查询改写**（4） | LLM 改写 + 子问题拆分 | 无 | 指代消解与检索友好化的拆分标准 | 改写用哪个服务等级（D-05/D-06）；改写失败是否降级为原问题 |
| **阶段 3 意图识别**（5~7） | 意图树 + 一次 LLM 给 N 个节点打分 + 并行调度与总量封顶 | 无 | 树形结构、`description` 决定匹配质量、封顶策略、"错误的路由比没有路由更糟" | **D-02（最该出 ADR 的一项）**；叶子绑知识库 ID 已定（§2）；管理界面已确认本期做 |
| **短路点 歧义引导**（8） | 检测到歧义时反问而非硬答 | 无 | 引导式问答的触发条件与话术结构 | 是否本期做（影响 Step B 的对话页 `ui-spec` 状态数） |
| **阶段 6a 检索动作映射**（9） | 意图分数 → 查哪个库、取多少条 | 无 | 分数到动作的映射思路 | 与 D-02 合并裁决 |
| **阶段 6b 多通道检索**（10） | 三通道并行查多个 Milvus Collection | `ChunkVectorRepository.search(kbId, queryVector, limit)`——**单库、纯向量、无全文/关键词通道** | 并行编排与结果归并骨架 | 通道构成（要不要加全文/BM25，pgvector 侧怎么做）；**同表按 kbId 过滤并行查 vs 多次单库查**（§2 已定方向，实现待定） |
| **阶段 6c 后处理精排**（11） | 30 条粗排 → 后处理流水线 → 5 条 | `RerankClient` 端口在，`aliyun-qwen3-rerank-cn` `enabled: false`；`2026-08-07` 实测**缺的是适配器不是凭据**（百炼 compatible-mode 无 `/reranks`，需 DashScope 原生适配器） | 流水线分段结构、Noop 降级必须显式标记"未重排"（Phase 2 已收敛） | 精排在本期是必需还是可降级；DashScope 原生 rerank 适配器算 Phase 3 工单还是 Phase 2 遗留 |
| **阶段 6d MCP 工具**（12~13） | 意图路由到 `mcpToolId` + 单独一次 LLM 提参 | `ToolDefinition` / `ToolCall` / `ToolResultMessage` 齐备，`ChatCommand.tools` 是一等参数，`OpenAiCompatibleChatProvider` 支持 `tools` 请求体、`tool_calls` 解析与流式增量 | **白名单按 Schema 过滤必须自己做**（Provider 层只校验"是合法 JSON"）；只读与有副作用工具必须区别对待 | **D-03**，且路线 A（意图路由+提参）与 B（原生 function calling）成本差已量化：B 省一次 LLM 调用但工具选择变黑盒 |
| **阶段 8a Prompt 组装**（14） | 三场景分模板 + 四段式骨架 + 证据放 user + 温度三档 | 无；`ChatMessage` 是 sealed（system/user/assistant/toolResult），四段式能原样拼出 | 场景分流、证据放 user 的三条理由、`<documents>`/`<tool-data>` 标签化 | **D-06 采样参数无处可放**；多条 `SystemMessage` 插数组中间供应商是否接受**需实测**；上下文预算无数据源（`ModelDeployment` 无窗口字段） |
| **阶段 8b 流式生成**（15） | 五层接力 + `StreamCallback` + OkHttp 读流 + 谁读流谁推送 | 第三、四层已建完且路线不同：`Flux<ChatStreamEvent>` + WebClient/Reactor（ADR 0004）；`RoutingChatClient`/`RoutingStreamingChatClient` 的 `@Configuration` 接线 `2026-08-07` 刚补上；**无任何 SSE 出口，前端无对话页** | 第一、二、五层的职责划分；`SseEmitterSender` 的 CAS 幂等模式 | **推送层必须显式切线程**（event loop 不能阻塞）；`ExecutionInfo` 不得原样透给前端；D-07 深度思考 |
| **阶段 8c 任务管控**（16） | Redis Bucket + Pub/Sub 跨集群取消，三道防线 | `CancellationToken`（含 `onCancel` 监听）+ `CallOptions.cancellation` + Reactor 订阅取消传播 | 取消时保存部分回复并回 `messageId`；前端先发停止再等确认事件 | **停止接口必须校验 taskId 归属**（原文完全没有）；**断连是否中断生成在 xrag 默认行为与原文相反**，要显式定 |

### 5.2 横切关注点

| 主题 | 课程方案 | xrag 当前实现 | 可复用部分 | 待决策问题 |
|---|---|---|---|---|
| Prompt 模板管理 | `.st` 文件 + `ConcurrentHashMap` 缓存，节点可整体替换 | 无模板体系 | 模板文件化与 section 渲染 | 存放位置、加载方式、热更新、"改 Prompt 算不算改代码"；**自定义必须是追加而非替换**（否则会静默删掉安全规则） |
| 不可信内容隔离 | 仅参数提取 Prompt 里一句声明，无标签转义 | 知识库上传通道**已上线**，chunk 来源不可信 | — | **D-08**，四类不可信输入需分类隔离 |
| ID 与统一语言 | `conversationId` + `taskId` | `logicalCallId` + `RequestId`（`ExecutionInfo`） | — | 四个 ID 的粒度与包含关系要在 Step C 前统一，并补进 [CONTEXT.md](../../CONTEXT.md) |
| 诊断与可观测 | 以日志为主 + 全链路 Trace 表 | `ai_logical_call` 五张表 + `/diagnostics` Trace Console 已上线 | 问答链路可挂到同一套诊断上 | 问答阶段耗时是否进现有诊断表，还是另起一套（受 [ADR 0005](../../docs/adr/0005-separate-model-runtime-state.md) 分层约束） |

### 5.3 已收敛的设计原则

1. **不为多实例预付复杂度，但要写明失效点与升级路径。** 取消广播、分布式信号量、ZSET 队列、防惊群全部不做，理由与 [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md) 逐条同构。
2. **但"改用数据库任务表"不是万能替换。** ADR 0002 的手法适用于秒到分钟级的入库任务；**取消与唤醒是亚秒级信号，单实例下的正确答案是"不需要跨进程通道"，不是"建张表"。**
3. **分布式机制与产品语义要分开裁决。** 拒绝 Redisson 不等于拒绝排队；`UploadConcurrencyFilter` 的 429 策略不能直接套到问答上。
4. **降级必须可察觉。** 参数提取失败退全默认值、持久化失败仍发 `FINISH` 且 `messageId` 为空——这类"用户看不出来的半失败"在 xrag 一律不接受，要么显式告知要么当作错误。
5. **对 LLM 的信任程度要前后一致。** 告诉模型的约束（枚举值、冲突处理规则、必填项）必须在代码侧复核，不能"说了就当它一定遵守"。
6. **端口语义优先于实现便利。** `StreamFailed` 强制 `incomplete=true`、`ChatMessage` 用 sealed interface——这类把语义钉进类型系统的做法要延续到问答链路的事件定义上。
7. **SSE 事件协议是独立契约，不是 `ChatStreamEvent` 的投影。** 它还要能表达排队超时、被拒等根本没进入模型调用的终态。
8. **不预留走不到的分支。** `PromptScene.EMPTY` 那种"一处抛异常、一处给默认值"的枚举值，要么彻底实现要么删掉（Phase 2 复盘 §5.2 同一条）。

### 5.4 不可照搬项汇总

| 原文方案 | xrag 约束 | 出处 |
|---|---|---|
| 分布式锁（Redisson）+ Watchdog 续期 | 不引入 Redisson，用状态字段 CAS 替代 | [ADR 0002](../../docs/adr/0002-knowledge-base-async-and-concurrency.md) |
| Redis 信号量控并发坑位 | 单实例假设，进程内信号量即可 | 同上 |
| MyBatis-Plus + `BeanUtil` 反射映射 | JdbcTemplate 直连 + 显式 RowMapper | Phase 2 工单 17 教训 |
| Milvus Collection | pgvector，同表按知识库 ID 过滤 | Phase 0 检查点 |
| `refreshCache` / `rewrite` 等空实现预留接口 | 不预留永远走不到的分支 | Phase 2 复盘 §5.2 |
| 自建 HTTP 调 LLM | 只能走 `com.app.ai` 四个能力端口 | [ADR 0003](../../docs/adr/0003-separate-model-capability-ports.md) |
| OkHttp + 专用线程池 + 自研 SSE 行解析 | WebClient + Reactor + 复用 Spring 事件解码器 | [ADR 0004](../../docs/adr/0004-reactive-streaming-runtime.md) |
| "谁读流谁推送"（读流与推 SSE 同线程） | 事件到达在 Reactor Netty event loop，`SseEmitter.send()` 是阻塞 Servlet IO，**推送层必须显式切线程** | [第 15 篇](05-qa-pipeline/05-15-流式生成的五层链路.md) |
| Redis Bucket + Pub/Sub 跨集群取消广播 | 单实例，进程内 `CancellationToken` + Reactor 订阅取消即可；**且不可机械套用 ADR 0002 的"改用数据库任务表"**——取消是亚秒级信号，轮询库延迟不可接受 | [第 16 篇](05-qa-pipeline/05-16-停止生成与跨集群取消.md) |
| `RPermitExpirableSemaphore` + ZSET + Lua claim + PollNotifier | 本地 `Semaphore(n, true)`，与 `UploadConcurrencyFilter` 同构；**但排队语义要保留，不沿用上传的 429 拒绝策略** | [ADR 0002 §4](../../docs/adr/0002-knowledge-base-async-and-concurrency.md)、[第 17](05-qa-pipeline/05-17-排队限流的入队与抢占.md)~[18 篇](05-qa-pipeline/05-18-异步等待与超时拒绝.md) |
