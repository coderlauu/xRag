# 分角色任务书：大模型调度引擎（xrag Phase 2 Step D~F）

## 1. Metadata

- `status`: in progress（Step D 工单 01、02、03、04、06、07、08、09、10、11、12 done；05、13、14、16、19 ready）
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

Step C 已于 2026-08-02 获确认。工单 01、02、03、04、06、07、08、09、10、11、12 已完成；当前前沿包含：

- 工单 05：只读调度总览 API；
- 工单 13：流式路由与故障转移；
- 工单 14：Embedding 批次与 mixed execution；
- 工单 16：Rerank 与显式 Noop；
- 工单 19：诊断保留与分批清理。

10 已完成同步 Chat 的 permission reject、retry wait、failover 与预算终止事件，并解锁 14/16；11 已完成 SSE adapter，12 已完成单候选流状态机并解锁 13。05/13/14/16/19 当前互不依赖，可独立推进，19 独立完成诊断保留边界。

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
- `2026-08-03`: Step D 工单 12 完成。落地 `StreamingChatClient` 与类型化公共事件、同一 publisher 单次订阅守卫、PROBING/COMMITTED/唯一终态 CAS、32 events/64KiB 探测、连接/响应头/首事件/首有效/idle/total 六阶段超时、取消传播与一次性诊断结算；STR-10~13、15~27、29、30、35 已自动化。`./scripts/ci-validate.sh` 通过（backend 318 tests，0 failures，2 skipped），解锁工单 13。
