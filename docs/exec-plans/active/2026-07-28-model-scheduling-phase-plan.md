# Exec Plan: Phase 2 大模型调度引擎

## 1. Metadata

- `phase`: Phase 2 / 大模型调度引擎
- `status`: in-progress（Step D 工单 01~22 done；23 缺合规 Key 保持 blocked；Step E/G 待项目所有者确认）
- `created`: 2026-07-28
- `owner`: liuqiang
- `parent`: [总执行计划](2026-07-24-ragent-column-learning-and-system-build.md)
- `source_group`: [04-model-scheduling](../../../learning/ragent-column/INDEX.md)
- `predecessor`: [Phase 1 里程碑状态](../../status/2026-07-28-phase-1-knowledge-base.md)

## 2. Objective

学习并验证专栏“大模型调度引擎”分组的 8 篇内容，在 xrag 中形成统一的 AI 基础设施层：上层业务只面向稳定接口调用 Chat、流式 Chat、Embedding 和 Rerank；调度层负责模型能力匹配、路由、熔断、故障转移和可诊断记录。

Phase 2 完成后，至少能演示：

> Chat、流式 Chat、Embedding 和 Rerank 通过各自稳定端口进入同一 AI 基础设施层 → 根据能力和策略选择 Provider/模型 → 首选模型失败或熔断时按契约切换 → 同步或 SSE 流式返回结果 → 调用记录能解释“选了谁、为什么切换、耗时多少”。

## 3. 边界

### In Scope

- Provider、模型和能力的统一领域模型。
- Chat 同步调用与公共执行模板。
- 多模型路由、三态熔断器与故障转移。
- SSE 流式解析、首包探测、超时和取消传播。
- Embedding 与 Rerank 的统一客户端接口。
- 把 Phase 1 已有的 Embedding 调用收敛到共享 AI 基础设施层，保持知识库行为不回归。
- 调度决策、失败原因、首包耗时和总耗时的可诊断记录。

### Out Of Scope

- 对话记忆、查询改写、意图识别、多知识库检索、Prompt 组装、MCP 工具调用和最终问答编排——这些属于 Phase 3。
- 检索与生成质量评测——属于 Phase 4。
- 多租户、计费、配额售卖和复杂权限体系。
- 在完成 Step A 学习和 Step B PRD 前预先冻结 Schema、API 或具体熔断库。

### 用户界面判断

Phase 2 的核心消费者是 Phase 3 后端，但路由和熔断状态具有明显的运维可视化价值。项目所有者已于 2026-07-28 确认采用推荐方案：

- 做一个轻量“模型调度诊断页”，展示 Provider/模型能力、候选过滤原因、熔断状态、最近调用与故障转移。
- 不做聊天产品页，不扩张为模型 CRUD、计费或复杂权限后台。
- 默认不保存或展示 Prompt、文档正文和模型输出，只记录调度事实和脱敏请求标识。

`ui-spec.md` 与可点击原型仍是前端实现前置闸门；具体字段、保留期和查看权限由 Step B 确认。

## 4. Plan

### Step A — 学习整理与检查点

状态：**complete（2026-07-28）**

按顺序完成 8 篇专题：

1. AI 基础设施层宏观设计。
2. 多模型路由与智能选择。
3. 三态熔断器与故障转移。
4. Chat 同步调用与模板方法。
5. SSE 流式解析与异步执行。
6. 流式路由的首包探测机制。
7. Embedding 向量化客户端。
8. Rerank 重排序与辅助工具。

产出：

- [x] `learning/ragent-column/04-model-scheduling/04-01...04-08` 学习整理稿。
- [x] [Phase 2 Step A 学习检查点](../../../learning/ragent-column/PHASE-2-STEP-A-CHECKPOINT.md)：课程方案、xrag 当前实现、可复用部分和待决策问题的对照。
- [x] [一手资料复核](../../research/2026-07-28-model-scheduling-primary-source-review.md)。
- [x] 更新 `learning/ragent-column/INDEX.md`：59/91。

闸门：项目所有者已于 2026-07-28 确认学习结论，已进入 Step B。

### Step B — PRD 与界面范围

状态：**complete（2026-07-28）**

先定义调度引擎服务谁、解决什么问题，再讨论实现：

- 定义模型能力、路由策略、故障转移和可解释性的产品规则。
- 明确 Provider/模型配置是文件配置还是可运行时管理。
- 明确失败时由系统自动切换还是向调用方暴露选择。
- 冻结已确认的轻量诊断页字段、保留期、查看权限和脱敏规则。
- 产出 PRD、界面规格与可点击原型。

已确认产品决策：

- Chat、流式 Chat、Rerank 默认由调度器选模型；Embedding 固定模型身份。
- 候选范围内的瞬时故障默认自动切换，普通调用方不能逐请求关闭。
- 服务等级为 `ECONOMY / BALANCED / QUALITY`，默认 `BALANCED`，不跨等级故障转移。
- 流提交后中断保留部分内容并明确 `STREAM_FAILED`，不混入其他模型。
- Provider/模型使用文件与环境变量配置，诊断页只读。
- 诊断记录保留 7 天或最多 10,000 次调用，沿用单用户访问边界且不保存正文。

产出：

- [x] [模型调度引擎 PRD](../../prd/2026-07-28-model-scheduling-prd.md)。
- [x] [模型调度诊断页界面规格](../../../tech/model-scheduling/ui-spec.md)。
- [x] [A/B/C 三方案可点击原型](../../../prototype/model-scheduling/index.html)。
- [x] 项目所有者确认 PRD、界面规格，并选择 A — Trace Console。

闸门：PRD 必须确认；若有界面，原型必须在写前端代码前确认。

### Step C — 技术方案与执行拆分

状态：**complete（项目所有者已于 2026-08-02 确认）**

产出：

- [x] [技术方案](../../../tech/model-scheduling/architecture.md)
- [x] [数据模型](../../../tech/model-scheduling/data-model.md)
- [x] [API 契约](../../../tech/model-scheduling/api.md)
- [x] [测试矩阵](../../../tech/model-scheduling/test-matrix.md)
- [x] [ADR 0003：按能力分离端口](../../adr/0003-separate-model-capability-ports.md)
- [x] [ADR 0004：WebClient/Reactor 流式运行时](../../adr/0004-reactive-streaming-runtime.md)
- [x] [ADR 0005：分离配置、熔断运行态与诊断历史](../../adr/0005-separate-model-runtime-state.md)
- [x] [分角色任务书](2026-07-28-model-scheduling-implementation-lanes.md)
- [x] [.scratch/model-scheduling 23 张原子工单草案](../../../.scratch/model-scheduling/README.md)

必须在本 Step 决策：

- 能力模型与 Provider SPI 的最小接口。
- 路由输入、确定性优先级和随机/权重策略是否需要。
- 熔断状态存放位置、时间窗口和单实例/多实例语义。
- 同步与流式调用能否共享执行骨架，哪些阶段必须分开。
- SSE 首包失败与流中失败的故障转移边界。
- Phase 1 `EmbeddingClient` 的兼容迁移路径。
- 外部模型供应商的条款、限额、费用和测试凭据。

当前草案方向：

- 四种能力保持分离业务端口，候选、预算、错误、熔断和诊断由 `com.app.ai` 内部共享。
- 确定性排序使用“默认候选 → priority → deployment ID”，第一版无随机、权重或学习型路由。
- 同步有限响应继续使用 `RestClient`；流式使用 `WebClient + Reactor`，应用服务端仍为 MVC/Tomcat。
- breaker 使用 Resilience4j，按 `provider/deployment/capability` 隔离并保存在当前 JVM。
- 完成态诊断异步写 PostgreSQL；配置在文件、秘密在环境变量、当前 breaker 在内存。
- Phase 1 `EmbeddingClient` 继续作为知识模块 seam，但 interface 在单张原子工单中收敛为 `embed(EmbeddingCommand) -> EmbeddingResult`；所有现有实现、Bean、启动校验和知识库调用点同时迁移，不长期保留两套重载。
- 推荐真实验证使用阿里云百炼通用计费 Key；当前未配置该 Key，只阻塞最终真实 Provider 准入。

Step C.1 评审修订：

- 统一错误分类、恢复动作和流式终态契约。
- 补齐可持久化的 route event 与 failure phase，使 Trace Console 能还原真实决策时间线。
- 明确 Embedding 多批次由多个等价 deployment 共同完成时的执行事实，以及单次订阅流契约。
- 冻结公共 Java 契约：封闭 Chat 消息、工具传输、finish/usage 归一、`ModelCallException` 及 Rerank 降级原因；`TOOL_CALLING` 由命令结构推导，不暴露可绕过过滤的开关。
- 使诊断 DDL 的 stream/capability、finish reason、Rerank 结果与降级原因约束和公开枚举对齐。
- 把原 14 张纵向工单重切为 23 张可独立验收的原子工单，修正首批并行与依赖关系，并分开验收隐私审计与诊断故障注入。
- 一致性验证覆盖 202 条可执行场景，已全部映射到 23 张工单；链接、锚点、JSON 示例、枚举/SQL 闭集和编号连续性均通过检查。

闸门：**已通过（2026-08-02）**。技术文档、测试矩阵和工单拆分已完成一致性复核并获项目所有者确认。

### Step D — 垂直切片实现

状态：**in progress（工单 01~22 已完成；23 缺合规 Key 保持 blocked）**

实现按 [23 张原子工单](../../../.scratch/model-scheduling/README.md) 的依赖前沿推进。当前已完成以下基础与能力切片：

1. 领域词汇、不可变模型目录与配置校验。
2. 纯诊断 DDL。
3. 四能力 Provider seam 与可编程假实现。
4. 完成态诊断快照、异步 writer 与原子落库。
5. 确定性 RoutePlan、工具能力推导与 Embedding work unit。
6. 单调 Deadline、attempt 预算与唯一错误动作表。
7. 单候选同步 Chat、OpenAI-compatible 有限响应 adapter、取消/绝对 deadline 与完成态诊断。
8. Breaker registry、原子 HALF_OPEN 许可、分类结算、运行快照与异步 transition 持久化。
9. 同步 Chat 有界原地重试、跨候选故障转移、429 独立配额边界与完整恢复事件。
10. WebClient/SSE Provider adapter、事件归一、断流分类与取消传播。
11. 单候选流单次订阅、探测/提交/唯一终态、阶段超时与诊断结算。
12. 流式路由与故障转移：提交前透明切换候选、提交后锁定 deployment 并只产生唯一 `StreamFailed`。
13. 调度层 Embedding 能力：按最小批量上限切 work unit、逐批独立恢复、已完成批次不重算与 mixed execution。
14. Phase 1 知识库 Embedding 原子迁移：显式身份、catalog 维度校验与已有绑定的改绑防护。

工单 15 已把 Phase 1 知识库调用点原子迁移到调度层，Embedding 自此只有一个入口。当前前沿并行推进 Overview API、Rerank/Noop 和诊断保留；随后再进入 Trace API/UI、交付与真实 Provider 准入。工单不按编号机械串行，但不得绕过依赖图。

闸门：浏览器或 API 演示统一入口、选路、熔断和故障转移。

### Step E — 测试矩阵执行

最低覆盖：

- 路由在相同输入下可确定复现。
- Provider 不支持所需能力时不会被选中。
- 熔断 `CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN` 全状态转换。
- 首选失败后只切到满足能力约束的候选模型。
- SSE 分片、跨 chunk JSON、首包超时、流中断、取消和资源释放。
- 非限流错误不盲目重试；限流和瞬时错误遵循已确认策略。
- 外部网络调用不进入数据库长事务。
- Phase 1 知识库向量化回归。
- 至少一次真实 Provider 的同步、流式、Embedding/Rerank 能力验证；不支持的能力必须明确记录而非伪造通过。

闸门：自动化与手工结果全部回写测试矩阵，并由项目所有者确认。

### Step F — CI/CD 与部署配置

- CI 使用可编程假 Provider，不调用收费 API。
- 真实 Provider 测试由显式环境变量守门。
- 部署示例登记 Provider、模型、超时、重试、熔断和路由配置，不提交真实密钥。
- 把 Phase 1 远端 CI 递延项与 Phase 2 新增门禁分开记录，避免旧问题污染新模块的完成口径。

### Step G — 复盘与里程碑状态

- 产出 `docs/retro/` 与 `docs/status/`。
- 记录真实故障转移证据、保留风险和 Phase 3 的消费契约。
- 经项目所有者确认后关闭 Phase 2。

## 5. Success Criteria

- 上层业务不依赖具体 Provider SDK 或供应商错误结构。
- 同步 Chat、流式 Chat、Embedding 和 Rerank 都通过各自稳定端口进入统一基础设施层。
- 路由结果可解释，故障转移有确定候选边界。
- 熔断状态机有自动化测试，不靠日志目测。
- SSE 取消后连接、线程和许可均被释放。
- Phase 1 知识库入库迁移后行为和向量不变量不回归。
- 外部 API 条款与限额在编码前完成准入检查。

## 6. Risks

| 风险 | 处理 |
|---|---|
| 8 篇课程方案与 xrag 现有 `EmbeddingClient` 重叠 | Step A 对照后决定复用或迁移，不并存两套基础设施 |
| 流式响应中途失败后无法安全切换 | Step C 明确“首包前可切换、首包后如何处理”的产品契约 |
| 熔断状态在多实例下不一致 | 先依据部署拓扑决策，不能默认本地内存实现等价于分布式 |
| 真实 Provider 能力和限额不一致 | 设计前核对官方条款与接口，测试按能力逐项守门 |
| 诊断页膨胀成完整管理后台 | 只展示调度事实；模型 CRUD、权限和计费不自动纳入 |

## 7. Current Node

- `now`: Step D 工单 01~22 全部完成。四个能力端口、路由/预算/熔断/故障转移、完成态诊断与保留清理、三个只读诊断 API、A — Trace Console 前端、隐私审计与 best-effort 证明、CI 与部署门禁均已落地并通过 `scripts/ci-validate.sh`（clean 构建后 backend 399 tests，0 failures，2 skipped）。
- `next`: **Step D 已无可自主推进的工单。** 工单 23（真实 Provider 准入）卡在外部条件——需要一把合规的通用计费 Key，现有 `EMBEDDING_API_KEY` 是 Coding Plan 凭据、architecture §13 明确不得扩展。之后是 Step E（测试矩阵手工项回写，含 DIA-18 崩溃恢复与 UI-01~08 浏览器走查）、Step F（已由工单 22 覆盖大部分）、Step G（复盘与里程碑状态），三者都需要项目所有者确认才能关闭。
- `decision_needed`: 两项待裁决——① 前端测试运行器：仓库从无前端测试，工单 18 引入 vitest 时被 pnpm store 版本不一致挡住（`node_modules` 由 10.7.1 装出、当前环境 10.12.3），需要整体重装依赖才能解决，已留给工单 22 连同把 `pnpm test` 接进 ci-validate 一起处理；② 工单 23 需要一把合规的通用计费 Key，没有就只能保持 blocked，不能伪造通过。
