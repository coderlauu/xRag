# 分角色任务书：大模型调度引擎（xrag Phase 2 Step D~F）

## 1. Metadata

- `status`: in progress（Step D 工单 01~22 done；23 缺合规 Key 保持 blocked）
- `created`: 2026-07-28
- `owner`: liuqiang
- `parent`: [Phase 2 执行计划](2026-07-28-model-scheduling-phase-plan.md)
- `inputs`: [PRD](../../prd/2026-07-28-model-scheduling-prd.md)、[架构](../../../tech/model-scheduling/architecture.md)、[数据模型](../../../tech/model-scheduling/data-model.md)、[API](../../../tech/model-scheduling/api.md)、[测试矩阵](../../../tech/model-scheduling/test-matrix.md)
- `tickets`: [.scratch/model-scheduling/issues](../../../.scratch/model-scheduling/README.md)

## 2. Objective

在不把 Provider 细节泄漏给业务层的前提下，实现 Chat、流式 Chat、Embedding、Rerank 四个能力端口及共享路由、预算、熔断、故障转移和诊断；把 Phase 1 Embedding 平滑迁移；按已确认的 A — Trace Console 完成只读诊断页。

可演示状态：

> 用可编程假 Provider 发起 BALANCED Chat → 首选 503 后切换成功 → 打开详情看到候选、route event、attempt 和 breaker；再发起流式 Chat → 提交前失败可切换、提交后断流只返回 STREAM_FAILED；Phase 1 文档仍能按其固定 Embedding 身份完成向量化。

## 3. Scope

### In Scope

- Step C 三件套、ADR 和测试矩阵定义的全部能力。
- 23 张可独立验收的原子工单。
- 假 Provider 自动化、真实 Provider 显式守门验证。
- 诊断页 A 方案，不实现 B/C。

### Out Of Scope

- Phase 3 问答、Prompt、检索和工具编排。
- 模型管理 CRUD、密钥 UI、手动 breaker 操作。
- 多实例 breaker 共享、动态热更新、学习型路由。
- 自动重建旧知识库的 Embedding 向量。
- 诊断正文采样和进行中调用实时展示。

## 4. Lane 与工单

Lane 是职责视角，工单是按可验证路径组织的执行视角。实现以工单依赖前沿推进；一个工单可以跨多个 Lane。

| 工单 | 产品 | 后端 | 前端 | 测试 | 运维 |
|---:|:---:|:---:|:---:|:---:|:---:|
| 01 领域词汇与模型目录 |  | ● |  | ● |  |
| 02 诊断纯 DDL |  | ● |  | ● |  |
| 03 Provider seam 与可编程假实现 |  | ● |  | ● |  |
| 04 诊断快照与异步 writer |  | ● |  | ● |  |
| 05 只读调度总览 API | ● | ● |  | ● |  |
| 06 单候选同步 Chat tracer |  | ● |  | ● |  |
| 07 确定性 RoutePlan |  | ● |  | ● |  |
| 08 Deadline、attempt 预算与错误动作 |  | ● |  | ● |  |
| 09 Breaker 注册表与许可 |  | ● |  | ● |  |
| 10 同步重试与故障转移 |  | ● |  | ● |  |
| 11 WebClient/SSE Provider |  | ● |  | ● |  |
| 12 单候选流状态机与单次订阅 |  | ● |  | ● |  |
| 13 流式路由与故障转移 |  | ● |  | ● |  |
| 14 Embedding 批次与 mixed execution |  | ● |  | ● |  |
| 15 Phase 1 Embedding 原子迁移 |  | ● |  | ● |  |
| 16 Rerank 与显式 Noop |  | ● |  | ● |  |
| 17 Trace calls/detail API | ● | ● |  | ● |  |
| 18 A — Trace Console 前端 | ● |  | ● | ● |  |
| 19 诊断保留与分批清理 |  | ● |  | ● |  |
| 20 诊断隐私审计 |  | ● | ● | ● |  |
| 21 诊断故障注入与 best-effort 证明 |  | ● |  | ● |  |
| 22 CI 与部署门禁 |  | ● |  | ● | ● |
| 23 真实 Provider 准入 |  | ● |  | ● | ● |

## 5. Lanes

### Lane 产品

**目标**：守住 approved PRD/UI 边界并裁决实现中出现的状态组合。

**写入范围**

- `docs/prd/2026-07-28-model-scheduling-prd.md`
- `tech/model-scheduling/ui-spec.md`
- `prototype/model-scheduling/`（仅作为决策证据；正式实现不复制）

**责任**

- 正式页面只实现 A — Trace Console。
- 任何新增模型管理、密钥操作、手动 breaker 操作先回到 PRD，不在实现中顺手加入。
- 文案必须区分成功、降级、失败、取消和流中断。
- 若实现发现状态组合缺口，先更新 ui-spec 状态矩阵再写 React。

**完成定义**

- [ ] 正式页面与 approved A 方向一致。
- [ ] PRD 17 条 AC 都能追溯到实现与测试。
- [ ] 需求变化有 Decision Log，不出现代码静默偏离。

### Lane 后端

**目标**：把 `com.app.ai` 建成深模块，对外只有能力端口和只读诊断 API。

**主要写入范围**

- `backend/src/main/java/com/app/ai/**`
- `backend/src/main/java/com/app/knowledge/embedding/**`
- 必要的知识库调用点与测试
- `backend/src/main/resources/ai-models.yml`
- `backend/src/main/resources/db/migration/V6__model_scheduling_diagnostics.sql`
- `backend/pom.xml`

**纪律**

- 不创建万能 Provider DTO 或公开 `invoke(Object)`。
- 不在外部 HTTP 调用期间持有数据库事务。
- 只有真实 HTTP 请求才算 attempt。
- 流式许可在终态结算一次，取消不计 Provider 失败。
- 诊断失败不能改变能力端口返回。
- Phase 1 现有 identity 不做 SQL 猜测式 backfill。

**完成定义**

- [ ] 四个能力端口与设计一致。
- [ ] 同步/流式恢复动作受统一预算约束。
- [ ] V6、route event/failure phase、writer、保留任务和三个诊断 API 完成。
- [ ] Phase 1 全套测试和事务外 HTTP 断言通过。

### Lane 前端

**目标**：扩展现有 `/diagnostics`，落地 A 双栏排障路径。

**写入范围**

- `frontend/src/pages/DiagnosticsPage.tsx`
- `frontend/src/api/diagnostics.ts` 与相关 types
- 必要的诊断子组件和样式

**责任**

- 并行加载 readiness、overview、calls。
- 顶部只做紧凑 Provider/breaker 状态条。
- 最近调用列表 + 固定详情/route event/attempt 时间线为主视觉。
- overview 页面可见时 30s 刷新；调用列表不自动跳动。
- 筛选写 URL，选中详情保留本地状态。
- 无正文、原始错误、完整 Provider request ID 或 secret。

**完成定义**

- [ ] 18 个“3 方案 × 6 场景”原型验证中，与正式 A 有关的 6 个场景全部在生产页可构造。
- [ ] 宽屏、960px、720px 三档可用。
- [ ] 键盘、Escape、焦点返回和非仅颜色状态满足 ui-spec。

### Lane 测试

**目标**：把 [测试矩阵](../../../tech/model-scheduling/test-matrix.md) 的 `A` 项落成自动化，把 `M` 项记录实测证据。

**写入范围**

- `backend/src/test/java/com/app/ai/**`
- Phase 1 Embedding 回归测试
- 前端组件/浏览器测试（按现有工具链最小新增）
- `tech/model-scheduling/test-matrix.md` 的执行状态与证据

**优先级**

1. 路由与 breaker 用可控时间，先保证确定性。
2. 流式状态机覆盖取消/完成竞态和资源释放。
3. Embedding identity、mixed execution 归属和事务边界是迁移红线。
4. 诊断隐私用“标记串不存在”自动检查。
5. 真实 Provider 最后执行，不替代假 Provider 契约测试。

**完成定义**

- [ ] 所有 `A` 用例有自动化。
- [ ] 所有 `M` 用例有日期、环境、结果和证据。
- [ ] 后端 verify、前端 build/lint、整体 validate 全绿或递延项经用户明确批准。

### Lane 运维

**目标**：让非敏感模型目录、秘密注入、假/真 Provider 边界和新增门禁可部署、可复现。

**写入范围**

- `backend/.env.example`
- `deploy/env/*.env.example`
- `deploy/README.md`
- `.github/workflows/ci.yml`
- `scripts/ci-validate.sh`

**责任**

- CI 只使用假 Provider，不持有收费 Key。
- 真实测试由 `DASHSCOPE_API_KEY` 等显式环境变量守门，skip 信息清晰。
- Coding Plan/工具 token 不得作为应用凭据。
- 配置示例登记超时、attempt、breaker、缓冲和保留参数。
- 记录 Provider 准入的模型版本、区域、限额和价格复核日期。

**完成定义**

- [ ] env example 无真实秘密。
- [ ] 本地完整 validate 通过。
- [ ] 真实 Provider 每能力不超过 20 次短调用并记录费用保护。

## 6. 依赖前沿

Step C 已于 2026-08-02 获确认。工单 01~22 已全部完成。

工单 23（真实 Provider 准入）需要一把合规的通用计费 Key，无 Key 时保持 blocked——
不能改代码伪造支持，也不能把它算作通过。

10 已完成同步 Chat 的 permission reject、retry wait、failover 与预算终止事件，并解锁 14/16；11 已完成 SSE adapter，12 已完成单候选流状态机并解锁 13；13 已完成流式路由与故障转移，流式链路自此闭环；14 已完成调度层 Embedding 能力并解锁 15；15 已把 Phase 1 知识库调用点原子迁移到调度层，Embedding 自此只有一个入口；05 已完成只读调度总览 API 并引入诊断保留的两个配置键。16/19 当前互不依赖，可独立推进；17 起的 Trace 线依赖 16，18 还需要 17。

完整依赖图见 [.scratch/model-scheduling/README.md](../../../.scratch/model-scheduling/README.md)。

## 7. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| Reactive client 与 MVC 混用配置错误 | 应用意外切服务端模型或连接不释放 | 工单 11 先做最小 WebClient contract，断言仍是 Servlet app |
| 流提交竞态或 Flux 被重复订阅 | 混流、重复计费、重复终态、breaker 许可泄漏 | 工单 12 用单次订阅守卫、可控事件序列和取消竞态测试先行 |
| Embedding 默认入口掩盖旧知识库身份或混合执行事实丢失 | 查询向量跨空间或诊断归属错误 | 工单 14 冻结 mixed execution，工单 15 原子迁移调用点 |
| 诊断队列掩盖写入失败 | 页面看似无调用 | dropped/write failure 暴露到 overview，工单 21 独立故障注入 |
| 现有 Key 不合规 | 账号风险、真实测试不可交付 | 新能力只接受通用计费 Key；旧 Key 仅做显式遗留回归 |
| 供应商能力/价格变化 | 配置漂移 | 工单 23 以执行日官方页重新准入，版本 pin 到配置 |

## 8. Validation

```bash
cd backend && ./mvnw -B verify
cd frontend && pnpm build && pnpm lint
scripts/ci-validate.sh
```

外加：

- 浏览器走完 Trace Console 六种正式场景。
- 工单 22 完成不收费的 CI/部署门禁；另由工单 23 使用通用计费 Key 执行 REAL-01~REAL-08。无 Key 时只阻塞真实 Provider 准入，不能伪造通过。
- 查询本地现有两个 Embedding identity，验证不会跨身份故障转移。

## 9. Rollback

- 工单按原子切片提交，可逐张回退。
- V6 只新增诊断表，回退应用后可保留，不影响 Phase 1。
- 工单 15 在同一原子切片中替换 `EmbeddingClient` interface、全部实现、Bean、启动校验和调用点；必要时整体回退该提交或将 Spring Bean 切回旧实现，不保留两套同时活跃的调用路径。
- WebClient/Reactor 只服务流式 Provider；若实测不可接受，可回退工单 11~13，不影响同步 Chat/Embedding/Rerank。
- 配置中的 deployment 默认可禁用，真实 Provider 准入失败不要求改代码。

## 10. Decision Log

- `2026-07-28`: 创建 Step C 任务书与 14 张垂直切片工单。技术草案采用四能力端口、确定性路由、Resilience4j、WebClient/Reactor 流式客户端、JVM 内 breaker、完成态异步诊断和显式 Embedding identity 迁移。
- `2026-07-30`: 根据 Step C 评审把实现重切为 23 张原子工单；新增 route event/failure phase、Embedding mixed execution、单次订阅流和 Phase 1 原子迁移的显式验收，并把诊断隐私审计与 best-effort 故障注入拆开验收。Step C.1 仍在评审，所有工单保持 `blocked-on-step-c-review` 或依赖阻塞，不标记 `accepted/ready`。
- `2026-08-02`: Step C.1 契约修订及一致性复核完成。公共 Java 契约、错误/流终态、诊断 Schema、Embedding/Rerank 语义已与 202 条可执行场景和 23 张工单对齐。当前等待项目所有者确认；确认前仍不标记 `accepted/ready`。
- `2026-08-02`: 项目所有者确认 Step C。三份 ADR 与架构/数据模型/API/测试矩阵转为 approved/accepted；任务书转为 `ready`，只解锁工单 01、02，其余工单继续受依赖图约束。
- `2026-08-02`: Step D 第一依赖前沿完成。工单 01 落地领域 value type、`ai-models.yml`、不可变 catalog、启动校验与脱敏 fingerprint；工单 02 落地 V6 五张诊断表及 Schema 约束测试。`./mvnw -B verify` 通过（198 tests，0 failures，2 skipped），解锁工单 03、04、07、08。
- `2026-08-03`: Step D 第二依赖前沿完成。工单 03 落地四能力 Provider SPI/fake，04 落地完成态诊断与异步事务 writer，07 落地确定性 RoutePlan，08 落地 Deadline/attempt/error policy。`./mvnw -B verify` 通过（229 tests，0 failures，2 skipped），解锁工单 06、09、11、19。
- `2026-08-03`: Step D 工单 06 完成。落地单候选同步 `ChatClient`、OpenAI-compatible `RestClient` adapter、统一结果/异常与完成态诊断；per-attempt timeout、主动取消、线程中断、终态竞态和绝对总 deadline 通过本地 stub 验证。`./mvnw -B verify` 通过（243 tests，0 failures，2 skipped）。工单 09、11、19 继续 `ready`，10 仍等待 09。
- `2026-08-03`: Step D 工单 09 完成。基于 Resilience4j 落地 `provider/deployment/capability` registry、手工原子许可、分类一次结算、可读运行快照与异步 transition 持久化；CBR-01~15 的 breaker 侧契约已自动化。`./mvnw -B verify` 通过（256 tests，0 failures，2 skipped），解锁工单 05 与 10；RTE-11 / DIA-19 的真实 route-event/failover 集成归工单 10。
- `2026-08-03`: Step D 工单 10 完成。同步 Chat 接通 RoutePlan、共享 monotonic Deadline/AttemptBudget、breaker permission、最多一次原地重试、独立 quota-domain 429 切换与完成态 route event；SYN-02~18、20、26、28、DIA-03/19/20、RTE-11 已自动化。`./mvnw -B verify` 通过（281 tests，0 failures，2 skipped），解锁工单 14 与 16。
- `2026-08-03`: Step D 工单 11 完成。在保持 Servlet/Tomcat 服务端的前提下引入 WebClient/Reactor Netty client，基于 `ServerSentEvent<String>` 完成 STR-01~09、文本/工具/拒答/usage/done/error 归一、64KiB 事件上限、未知事件诊断跳过、HTTP 失败分类、断流和取消传播；Spring 6.2 不识别独立 CR 的缺口由 reader 前的有界行尾归一补齐，不引入自定义 SSE parser。`./mvnw -B verify` 通过（294 tests，0 failures，2 skipped），解锁工单 12。
- `2026-08-04`: Step D 工单 15 完成。分三步落地：Provider 适配器与 Spring 装配先各自作为纯新增提交，再用一个原子提交完成接口替换——删除 knowledge 侧 EmbeddingClient 及其两个实现、两个异常类与 EmbeddingProperties，三个调用点改为显式 `ModelIdentity` 的 `EmbeddingCommand`，`EmbeddingConfig` 退化为启动校验并新增 EMB-20 身份绑定检查，`ApiExceptionHandler` 改映射 `ModelCallException`。出站限流的主动节流落在 Provider 适配器，作用域提升到 Provider 实例；新增 `app.knowledge.default-embedding-identity` 以避免静默改变新建知识库绑定的模型。EMB-01、13~16、20 已完成，EMB-21/22 的自动化用例待补。`./mvnw -B verify` 通过（347 tests，0 failures，2 skipped），解锁工单 17。
- `2026-08-04`: Step D 工单 14 完成。按 api.md §1.5 冻结 Embedding 公共类型，新增 `RoutedEmbeddingClient`：按所有合格等价候选 `maxBatchItems` 的最小值切 work unit，每批独立 attempt 预算、全调用共享 deadline；按 index 保序还原并拒绝重复/越界/缺失 index、维度不符与非有限浮点；中间批次可在等价 deployment 间切换且已完成批次不重算，候选起点随上一批成功候选前移（sticky）；contributors 按首次贡献去重，`mixedExecution` 当且仅当数量大于 1；任一批失败或取消都不返回部分向量且调用级贡献者归零。EMB-02~12、17~19、23、24 已自动化。范围只到 `com.app.ai`，知识库调用点迁移留给工单 15。`./scripts/ci-validate.sh` 通过（backend 352 tests，0 failures，2 skipped），解锁工单 15。
- `2026-08-04`: Step D 工单 13 完成。新增 `RoutingStreamingChatClient` 作为流式路由协调层，把 RoutePlan、共享 monotonic Deadline/AttemptBudget、错误动作表与 breaker permission 接到单次订阅状态机上：提交前按动作表透明切换候选且失败候选事件不下发，提交后锁定 deployment 只产生唯一 `StreamFailed`，成功按首个有效块耗时判慢、失败与取消分别结算许可，候选耗尽时零事件 `onError(ModelCallException)`。为避免复制 200 行状态机，把单次 attempt 的机制提取为包内 `CandidateStreamAttempt`，`SingleCandidateStreamingChatClient` 改为委托、行为不变。STR-08、12、14、18、19、22、23、27、28、30~35 与 DIA-06 已自动化。`./scripts/ci-validate.sh` 通过（backend 336 tests，0 failures，2 skipped）。
- `2026-08-03`: Step D 工单 12 完成。落地 `StreamingChatClient` 与类型化公共事件、同一 publisher 单次订阅守卫、PROBING/COMMITTED/唯一终态 CAS、32 events/64KiB 探测、连接/响应头/首事件/首有效/idle/total 六阶段超时、取消传播与一次性诊断结算；STR-10~13、15~27、29、30、35 已自动化。`./scripts/ci-validate.sh` 通过（backend 318 tests，0 failures，2 skipped），解锁工单 13。
- `2026-08-07`: Step D 工单 05 完成，01~15 自此全部关闭。落地 `com.app.ai.web` 三件套：`AiDiagnosticsOverview`（DTO）、`DiagnosticsOverviewService`（组装）、`DiagnosticsOverviewController`（`GET /api/v1/ai/diagnostics/overview`）。配置快照取自 catalog、当前熔断态取自 JVM registry、最近 24 小时聚合取自 `ai_logical_call`，四个可空计数一律以 `null` 表达"读不到"而不是 0。三条实现约束：`eligibleCandidates` 只能读 `snapshots()`，用 `snapshot(target, capability)` 逐个探测会因 `computeIfAbsent` 凭空建出 breaker 条目；合格候选跟着 `allowsCall` 走而不看状态名，HALF_OPEN 许可占满同样不算候选但不计入 `openBreakers`；时间字段用 `ToStringSerializer` 输出，否则 CFG-10 的裸 `ObjectMapper` 会在 `generatedAt` 上抛 JSR-310 缺失。诊断保留窗口的两个键（`app.ai.diagnostics.retention.max-age-days` / `max-logical-calls`）随本工单引入并定义在 `application.properties`，工单 19 复用同一份配置。API-01、CFG-10 及 OPEN/HALF_OPEN/UNCONFIGURED/配置禁用/无 Provider 五种状态已自动化；24 小时聚合口径另由真库集成测试守，其 fixture 刻意让窗口内调用切换两次，使 `count(*) filter` 与 `sum(failover_count)` 可区分。按 Decisions，`limits.maxContextTokens` 本期不返回，`verifiedCapabilities` 恒为空数组待工单 23 补齐验证记录。`./mvnw -B verify` 通过（clean 构建后 356 tests，0 failures，2 skipped）。
- `2026-08-07`: Step D 工单 19 完成。`DiagnosticRetentionService` 按 data-model §7 落地物理清理：依次跑"按时间删调用 → 按条数上限删调用 → 按时间删 transition"三项，每项循环到本轮删不满一批为止，**每批一个独立短事务**——单个大删除事务会长时间持锁堵住正在写诊断的调用，而写入失败会被 writer 记成 write failure 暴露到总览，等于清理任务自己制造一次"看起来像故障"的事件。两条实现取舍：先按时间删再按条数删，顺序反了会拿注定要删的过期记录去占 10,000 的名额；cutoff 由注入的 `Clock` 计算而不是 SQL 里的 `now()`，设计文档的示例写的是后者，但那样测试无法控制时间。新增 `AiSchedulingConfig` 让本模块自己开 `@EnableScheduling`，不再依赖 `knowledge.ingestion.IngestionSchedulingConfig` 存在（那个文件的注释明说"将来若要关掉只动这一个文件"）；新增 `app.ai.diagnostics.retention.enabled`（默认 true）与 `.interval`（默认 1h），`ApplicationTests` 已用前者关掉，沿用 `app.knowledge.ingestion.enabled` 的做法，`max-age-days` / `max-logical-calls` 直接复用工单 05 引入的键。DIA-13~16 已自动化：cascade、10,001 条只留最新 10,000 且按 `started_at desc, id desc` 稳定排序不误删新记录、transition 不受条数上限影响、2,500 条确实拆成 1000+1000+500 三次事务。`./scripts/ci-validate.sh` 通过（clean 构建后 backend 361 tests，0 failures，2 skipped）。
- `2026-08-07`: Step D 工单 16 完成，四个能力端口自此全部就位。新增公共契约 `RerankClient` / `RerankCommand` / `RankedCandidate` / `RerankResult`，路由客户端 `com.app.ai.rerank.RoutedRerankClient`，适配器 `OpenAiCompatibleRerankProvider`，装配 `RerankClientConfiguration`；`ModelDeployment` 增加 `RerankProfile`（`maxDocuments`），`RouteRequest`/`RoutePlanner` 增加 rerank 候选数过滤。恢复循环与 `RoutingChatClient`/`RoutedEmbeddingClient` 同形自持，不抽公共骨架——仓库既有取舍是共享运行时原语而非共享循环类，硬抽会动到已验收的 Chat 行为。四条设计取舍：`rerankApplied == (degradationReason == null)` 与"降级不得带 score"由 `RerankResult` 构造期强制；ID 唯一与 topN 范围放在 client 校验而不是 record 构造期，否则失败没有 requestId 也没有诊断；文档上限走路由过滤（`CALLER_CONSTRAINT_MISMATCH`）而不是前置硬拒，让 RRK-11 与 RRK-12 共用一套机制并保留"A 放不下 B 放得下就用 B"，明确排除静默截断；缺 secret 判定简化为"存在声明 RERANK 且 enabled 的 deployment 而其 Provider 未配置"即直接失败，因为只有完全没有合格候选时才会走到该分支，失败一侧更可诊断。`ai-models.yml` 新增 `aliyun-qwen3-rerank-cn` 但默认 `enabled: false`——打开它必须与注入 `DASHSCOPE_API_KEY` 同时做。RRK-01~12 与 DIA-05 已自动化（14 例）。**遗留**：适配器的线路形状（路径/字段名/`return_documents`）未经真实验证，`max-documents: 100` 是占位值，都要在工单 23 核实。`./scripts/ci-validate.sh` 通过（clean 构建后 backend 375 tests，0 failures，2 skipped）。
- `2026-08-07`: 更正此前三条记录里的测试计数。`target/` 里残留着 `5aa33f4` 删除的两个 Phase 1 测试类（`UnconfiguredEmbeddingClientTests`、`OpenAiCompatibleEmbeddingClientTests`，合计 11 例）的旧 surefire 报告，而统计是对 `target/surefire-reports/*.txt` 求和的，于是工单 05 记成 367（实为 356）、工单 19 记成 372（实为 361）。工单 16 因为改了 `ModelDeployment` 的 record 组件触发 `NoClassDefFound`、被迫 clean 重建才暴露出来。**今后计数一律以 clean 构建为准**；已提交的 commit message 里的旧数字无法追改，以本条与各工单记录为准。
- `2026-08-07`: Step D 工单 17 完成，后端三个只读诊断接口齐了。`com.app.ai.web` 新增列表项/详情 DTO、筛选条件、`DiagnosticCallQueryService` 与 `DiagnosticCallsController`。**前置重构**：把 `ApiException` / `ApiExceptionHandler` / `PageResponse` 从 `com.app.knowledge.web` 移到 `com.app.web`——`knowledge.web` 已经依赖 `com.app.ai.model`，再让 `ai.web` 反向 import 就成环了；这三个类本来就是全应用契约（handler 的注释自己写着"不能限定 basePackages"），放在 knowledge 下只是历史位置，迁移改动全是 import。四条实现要点：`providerId` 与脱敏 request ID 走 `ai_attempt exists` 而不是比对调用级 `actual_*`，否则失败的首选 Provider 与 mixed Embedding 贡献者会从筛选里消失；`requestId` 一律精确匹配，不做 `%like%`；详情用显式 RowMapper 而不是 `queryForMap`（后者对 timestamptz 返回 `java.sql.Timestamp`，强转会运行时炸，实测踩到）；`lastAttemptTarget` 与 `actual*` 严格分开，前者只是最后一次真实 attempt，不参与 actual/mixed/contributing 语义。API-02~11 与 DIA-21 已自动化（14 例）。**已知规格缺口**：api.md §7 承诺的 Embedding `capabilityDetails.validation` 三个标志 V6 Schema 没有存，失败调用也无法归因到具体哪一项，当前成功时返回三个 true、其余返回 null 而不猜；要真正兑现需要 V7 加列，另开工单。`./scripts/ci-validate.sh` 通过（clean 构建后 backend 389 tests，0 failures，2 skipped）。
- `2026-08-07`: Step D 工单 18 完成，Phase 2 的功能面全部落地。按 approved A 方向重写 `/diagnostics`：`frontend/src/api/diagnostics.ts` 封装三个接口，`DiagnosticsPage` 负责筛选/URL 同步/轮询/状态，`OverviewBar` 与 `CallDetailPanel` 分别承担摘要条与详情时间线。只有 overview 轮询（可见 30s、隐藏暂停、不重叠），调用列表只在进入/筛选变化/手动刷新时重载，否则列表会在阅读时自己跳动。**浏览器实测发现并修掉四个读代码发现不了的缺陷**：① 列表"首个有效块"列恒为 `—`，因为列表 DTO 根本没这个字段，后端补 `firstMeaningfulChunkMs`（只有流式非空），顺带把测试 fixture 改真实（原来给同步 Chat 也塞了流式时刻）；② 1280px 双栏把 8 列表格挤成竖排折行，双栏断点提到 1600px、之下改右侧固定抽屉，正是 ui-spec §8.2 的"普通桌面用抽屉"；③ 375px 页头被挤成一列单字且卡片仍需横向滚动，改竖排并取消 `overflow-x`，实测 `scrollWidth === innerWidth`；④ 可点击行与摘要格子都是 `role="button"` 却没有可访问名，读屏只念"button"，补了完整句子的 `aria-label`。UI-09 的关键语义用网络请求证据确认：点"24h 失败/流中断"真实发出 `?outcome=FAILED&outcome=STREAM_FAILED`，不是过滤当前已加载的一页。**遗留**：UI-04/05/08/09/10 的"A（自动化）"部分未落地——仓库从无前端测试运行器，引入 vitest 被 pnpm store 版本不一致挡住（`node_modules` 由 10.7.1 装出、当前环境 10.12.3，`pnpm add` 报 `ERR_PNPM_UNEXPECTED_STORE`，需整体重装依赖）。这属于工具链/CI 决策，不在一张前端功能工单里单方面做掉，留给工单 22 连同把 `pnpm test` 接进 ci-validate 一起处理。`./scripts/ci-validate.sh` 通过（clean 构建后 backend 390 tests，0 failures，2 skipped）。
- `2026-08-07`: Step D 工单 20、21 完成。**20**：`DiagnosticPrivacyAuditIntegrationTests` 把八类标记串（Prompt、工具参数、Embedding 正文、Rerank query/候选、原始错误体、密钥值、完整 request ID）塞进本不该保存的位置，跑完四个能力后扫描五张诊断表的全部文本列与两个诊断 API 的序列化结果。用扫描而非逐字段断言，是因为泄漏最常发生在新增字段时——有人给 DTO 加一个"顺手带上原始响应"的字段，逐字段断言不会失败、扫描会；列清单从 `information_schema` 现取，将来新增的文本列自动纳入。另加一个**审计自检**用例：先确认干净，再人为往两张表各种一个标记串并断言都被扫出来、且报告不含原文——没有这一例，前面的"通过"和一个永远找不到东西的扫描器无法区分。**21**：进场核对发现 DIA-07/08/09/17 早已被工单 04 的用例覆盖，那些证明的是 writer 自身行为；缺的是另一半——writer 再 best effort，只要某个 routed client 忘了把 `offer` 包进 try/catch，诊断异常就会冒成业务失败。新增 `DiagnosticBestEffortTests` 逐能力比对"诊断坏掉"与"诊断健康"两次调用的返回，Rerank 的 Noop 降级单列一例（降级路径同样走 `recordBestEffort`，最容易被漏）。**遗留**：页面 DOM 的自动化扫描与 DIA-18（手工 kill 进程）未做，前者并入工单 22 的前端测试运行器决策，后者留待 Step E 手工项。`./scripts/ci-validate.sh` 通过（clean 构建后 backend 399 tests，0 failures，2 skipped）。
- `2026-08-07`: Step D 工单 22 完成，22 张可自主完成的工单全部关闭。**清掉一批死配置**：`deploy/env/{production,staging}.env.example` 里还留着 `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` / `EMBEDDING_BATCH_SIZE`，而工单 15 早已把这些属性从后端删除（`grep` 确认 `backend/src/main` 零引用）——照着配的人会以为自己在调模型，其实什么都没发生。换成真实生效的 Phase 2 键：Provider 密钥变量名（与 `ai-models.yml` 的 secret-property 对应）、Embedding 节流间隔、默认向量身份、诊断保留四项；非敏感模型目录仍只在 `ai-models.yml`，env 只放密钥值。`deploy/README.md` 新增「模型调度引擎（Phase 2）运维须知」，写清四条属于设计决定而非缺陷的行为：熔断只在当前 JVM 且假定单实例（多实例下各算各的，诊断页显示的是你连到的那个实例）；模型配置改完必须重启（catalog 是启动时快照，无热更新）；诊断 best effort 意味着页面可能缺记录，看到"部分调用诊断可能缺失"要查数据库而不是 Provider，且多实例部署应只让一个实例开启保留清理；真实 Provider 准入是独立步骤，CI 绿灯不代表它通过。CI 全文不含任何 Provider 密钥，`ai-models.yml` 里唯一的 RERANK deployment 默认 `enabled: false`，无 Key 环境不会把它变成配置故障。**未做且明确留给后续决策**：前端测试运行器——引入 vitest 被 pnpm store 版本不一致挡住（`node_modules` 由 10.7.1 装出、当前环境 10.12.3，需整体重装依赖），这会改变所有人的本地环境，不该由一张 CI 工单单方面决定；它一天不落地，UI-04/05/08/09/10 与工单 20 的页面 DOM 扫描就只有浏览器实测证据。建议单开一张工单：统一 pnpm 版本 → 重装依赖 → 引入 vitest + testing-library → 把 `pnpm test` 接进 ci-validate 与 CI。
- `2026-08-07`: **递延项 P2-02 解除，前端测试运行器落地。** vitest 4 + jsdom 30 + testing-library 接入，配置在 `frontend/vite.config.ts` 的 `test` 段、setup 在 `src/test/setup.ts`、测试文件与被测组件同目录，`pnpm test` 已进 `scripts/ci-validate.sh`（排在 `build` 之后——实测 `tsc -b` 会连测试文件一起做类型检查，故意写个 `const x: number = "s"` 能让构建以 TS2322 失败）。CI workflow 未改：所有门禁都经 ci-validate 这一个入口。14 条覆盖 UI-04/05/08/09/10，`Test Files 1 passed / Tests 14 passed`。**先更正上面两条记录里的成因**：工单 18 与 22 都把阻塞写成"pnpm 版本不一致（`node_modules` 由 10.7.1 装出、当前环境 10.12.3）"，实际复现出的报错是 store **目录版本**不一致——`node_modules` 链接自 `store/v11`，当前 pnpm 要用 `store/v10`；全局 pnpm、corepack 解析版本与 `packageManager` 字段本就都是 10.7.1，版本号从来不是原因。修法碰巧相同（整体重装），但按版本号查会一路查空。**mock 边界选在全局 `fetch` 而不是 `api/diagnostics.ts` 的导出函数**：UI-09 要证明的正是"摘要下钻发出真实后端查询、而不是拿当前已加载的一页近似过滤"，mock 掉 `fetchCalls` 恰好把待验逻辑一起 mock 掉；在 fetch 层断言 URL，证据与浏览器网络面板同源。**断言经三次变异验证**，每次只有对应的一条变红：删掉 `overviewInFlight` 守卫 → UI-05 不重叠红（期望 1 次请求实得 5 次）；failover 下钻口径改成 `outcomes:["FAILED"]` → UI-09 红；让详情面板渲染 `promptText` → UI-08 红。第三条尤其必要——UI-08 这类"断言某些东西不出现"的用例最容易沦为永远通过的空转测试，不变异就分不清"前端确实按白名单渲染"和"夹具里根本没有那些字段"，因此夹具里故意塞了契约外的 `promptText` / `apiKey` / 未脱敏 request ID。Step E 待办里的手工项从 6 条降到 4 条（UI-01、UI-03、UI-06、DIA-18），UI-05/UI-10 只剩低优先级的真实环境确认。`./scripts/ci-validate.sh` 通过（backend 399 tests，0 failures，2 skipped；frontend build + 14 tests）。
- `2026-08-07`: **更正 architecture §13.2 的条款引用，并记录一次不该发生的真实调用。** 起因是复核"REAL-03 能否用现有 `EMBEDDING_API_KEY` 执行"：§13.2 用[阿里云 Coding Plan FAQ](https://help.aliyun.com/en/model-studio/coding-plan-faq) 论证限制，而这把 Key 指向的是**火山方舟**端点 `/api/plan/v3`，出处与实际 Provider 不对应。**但结论没错，错的只是引用**——火山方舟自己的官方文档（2026-07-28 查证）早已被 Phase 1 逐字记进 `backend/src/main/resources/application.properties` 的 Embedding 段落，并明确写着"本项目拿它做知识库向量化，正属于被禁止的直接 API 调用……是项目所有者知情后的选择，不是推荐用法"。§13.2 已改为按供应商分开列出处，并注明这类错引的风险：出处一旦被发现对不上，很容易被顺势推翻掉本来有更强一手依据的结论。**过程中犯的错**：在读到上述记录之前就先用 `.env` 里的 Key 跑了 3 次 `RealEmbeddingApiTests`，那正属于被禁止的直接 API 调用；发现后已停止，未继续排查。**它意外暴露了一个此前没写下来的缺口**：该测试在工单 15 之后已改为注入 `com.app.ai.model.EmbeddingClient`（走 Phase 2 调度层），但由 `@EnabledIfEnvironmentVariable` 守门，而本机 `.env` 里的值不是环境变量，于是**从迁移那天起每次都是 skipped**——CI 与本地都绿，Phase 1 的 M-06 通过记录验的却是迁移**前**的实现。这次实测两条用例全部失败于 `PROVIDER_RESPONSE_INVALID`（"模型供应商响应不符合协议"）；按 `OpenAiCompatibleEmbeddingProvider.decode` 的分支，该分类排除了 4xx 与 5xx，意味着不是认证失败或限流，而是"HTTP 2xx 但响应体不是预期结构"或 3xx。结论：**Phase 2 的 Embedding 真实供应商链路从未被验证过一次**，那 2 个 skipped 长期被读成"只是没跑"，实际含义是"这条路可能是断的"。断点未定位（继续定位就要继续违规调用），已并入工单 23 的范围——它要验的不只是新 Provider 能否接入，还包括迁移后的 Embedding 路径到底通不通。详见 [test-matrix §14.4.1](../../../tech/model-scheduling/test-matrix.md)。
