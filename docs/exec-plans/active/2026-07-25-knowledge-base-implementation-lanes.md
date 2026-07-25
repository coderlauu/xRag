# 分角色任务书：AI 知识库建设模块（xrag Phase 1 Step D~F）

## 1. Metadata

- `status`: active
- `created`: 2026-07-25
- `owner`: liuqiang
- `parent`: [2026-07-24-ragent-column-learning-and-system-build.md](2026-07-24-ragent-column-learning-and-system-build.md)（本文件是其 Phase 1 的 Step C 产出、Step D~F 的执行依据）
- `inputs`: [PRD](../../prd/2026-07-25-knowledge-base-prd.md)（approved）、[技术方案](../../../tech/knowledge-base/architecture.md)、[数据模型](../../../tech/knowledge-base/data-model.md)、[API 契约](../../../tech/knowledge-base/api.md)、[ADR 0002](../../adr/0002-knowledge-base-async-and-concurrency.md)
- `tickets`: `.scratch/knowledge-base/issues/`（垂直切片工单，本文件的 Lane 是职责视角，工单是执行视角，两者是同一批工作的两个切法——见 §3）

## 2. Objective

在 xrag 中实现完整的知识库建设能力：知识库管理、文档上传（本地文件/远程 URL）、异步分块与向量化、定时同步、分块级手动干预，前后端打通并可在浏览器中完成全流程操作，同时产出企业级 SDLC 所要求的测试矩阵与 CI/CD 变更。

完成后的可演示状态：**创建知识库 → 上传一份 PDF → 触发分块 → 界面上看到分块列表 → 手动编辑其中一个分块 → 数据库与向量表数据一致**，全程在浏览器里完成。

## 3. Scope

### In Scope

[PRD](../../prd/2026-07-25-knowledge-base-prd.md) §4 全部四个功能域、§6 全部非功能需求，按 [技术方案](../../../tech/knowledge-base/architecture.md) 落地。

### Out Of Scope

- PRD §2 已列的全部非目标（多租户/权限、Pipeline 模式、复杂平台鉴权、精确 Token 计数、消息队列）。
- 检索与问答能力——本模块只负责把向量写对，读向量是 Phase 3 的事。
- 语义分块策略（推迟到 Phase 4 有评测数据后评估）。
- 对象存储空间回收、`ingestion_run` 历史归档（已知的增长代价，显式接受）。

### Lane 与工单的关系

同一批工作有两个切法，两者都要维护，不是冗余：

- **Lane（本文件）= 职责视角**。回答"谁负责什么、写哪些文件、完成的标准是什么"。这是企业级 SDLC 里的角色边界，也是这份产出物作为作品集材料的价值所在。
- **工单（`.scratch/knowledge-base/issues/`）= 执行视角**。按垂直切片（tracer bullet）组织，每张工单跨多个层次但只完成一条完整可验证的路径，并声明依赖边。这是实际动手时的推进顺序。

一张工单通常会同时落在多个 Lane 上（比如"上传文档"工单同时涉及后端、前端、测试三个 Lane）。**Lane 的完成定义是所有相关工单完成后的验收总和**，不要求 Lane 内部串行完成。

## 4. Lanes

---

### Lane 产品

**目标**：把 PRD 的交互要点细化成前端可以直接照着实现的界面说明，并在开发过程中裁决遇到的需求歧义。

**写入范围**
- `docs/prd/2026-07-25-knowledge-base-prd.md`（已 approved，仅在需求确实需要变更时修订，改动必须在本文件 Decision Log 留痕）
- `tech/knowledge-base/ui-spec.md`（新建：页面结构、状态展示规则、空态/错误态/加载态文案）

**依赖**：无，可立即开始。前端 Lane 依赖本 Lane 的 `ui-spec.md`。

**具体任务**
1. 输出 `ui-spec.md`，覆盖三个页面：知识库列表页、文档列表页（含上传入口与定时同步配置）、分块管理页（子页面或抽屉）。每个页面写清：布局结构、每种数据状态下展示什么、哪些操作在什么条件下禁用。
2. 明确定义四种文档状态（`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`）在界面上的中文表述与视觉区分方式，以及 `RUNNING` 时哪些按钮必须禁用。
3. 明确 `ingestion_run` 的 `SKIPPED` 状态怎么向用户解释——它不是失败，是"检查过、内容没变、无需处理"，措辞不能让用户以为出了问题。
4. 明确 `phase` 字段（`DOWNLOAD`/`EXTRACT`/`CHUNK`/`EMBED`/`PERSIST`）的中文映射，让失败信息对用户有意义。
5. 定义批量启用/禁用的操作反馈文案（PRD §5 要求展示"已选中 N 个，其中 M 个将发生变化"）。

**完成定义**
- [ ] `ui-spec.md` 三个页面全部有结构说明和状态规则，前端 Lane 确认无需再回来追问需求。
- [ ] 全部状态枚举、`phase` 枚举都有确定的中文表述，不在代码里临时决定。
- [ ] `node scripts/check-doc-links.mjs` 通过。

---

### Lane 后端

**目标**：实现 [API 契约](../../../tech/knowledge-base/api.md) 的全部 19 个接口，以及异步入库、定时同步、卡死回收三个后台机制，保证数据库与向量表在所有路径下一致。

**写入范围**
- `backend/src/main/java/com/app/knowledge/**`（新建，分层见[技术方案 §2](../../../tech/knowledge-base/architecture.md)）
- `backend/src/main/resources/db/migration/V2__knowledge_base_schema.sql`（新建）
- `backend/src/main/resources/application.properties`（新增 multipart 限制、Embedding、上传限流、定时同步四组配置）
- `backend/pom.xml`（新增 Apache Tika 依赖）
- `backend/src/main/java/com/app/config/`（若需新增线程池/Filter 配置类）

**不得写入**：`health/`（本模块不改健康检查——Embedding API 按次计费，不能放进 readiness 探活）、`config/StorageConfig.java`（复用现有 `S3Client` Bean，不新建客户端）。

**依赖**：无外部依赖可立即开始。但**必须先确认 Embedding API Key 已配置**才能验证端到端链路——这是唯一的外部阻塞项，需要用户提供。

**具体任务**（按依赖顺序，对应工单 01~10）
1. `V2` migration 建 5 张表；本地 `docker compose up -d postgres` 后确认迁移成功执行。
2. 配置补齐：multipart 三项大小限制（含容易漏掉的 `server.tomcat.max-swallow-size`）、Embedding 五项、`app.upload.max-concurrent`、`app.knowledge.sync.*`。
3. `EmbeddingClient` 接口 + OpenAI 兼容实现（`RestClient`，按 `batch-size` 分批）；未配置 API Key 时注入抛明确异常的实现，不阻塞应用启动。启动时校验 `dimensions` 与向量列维度一致。
4. 知识库 5 个接口 + 级联逻辑删除。
5. 上传限流 `Filter`（本地 `Semaphore`，**位置必须在 `Filter` 而非 `Interceptor`**）+ 文件上传接口 + URL 抓取接口。
6. Tika 文本提取 + 两种分块策略（`FIXED_SIZE` / `RECURSIVE`）+ 启发式 token 估算。
7. 异步入库：CAS 抢占、`@Scheduled` 轮询派发、`executeRun` 六步流程（**Embedding 必须在事务外算完再开事务写库**）、失败在新事务中标记。
8. 卡死回收两层：启动回收 + 心跳超时回收。
9. 定时同步：扫描、两级变更检测（HEAD → 内容哈希）、`SKIPPED` 记录、`next_sync_time` 推进、cron 最短间隔校验。
10. 文档管理 7 个接口 + 分块管理 6 个接口（批量接口必须用 `TransactionOperations`，方法本身不加 `@Transactional`）。

**完成定义**
- [ ] 19 个接口全部实现，行为与 [API 契约](../../../tech/knowledge-base/api.md) 一致（含全部错误码）。
- [ ] `cd backend && ./mvnw -q -B verify` 通过。
- [ ] 全部 `repository` 查询方法都带 `deleted = false`（这条靠约定维持、极易漏，需要逐个方法核对一遍）。
- [ ] 代码中不存在"事务内调用 Embedding API / 对象存储 / 远程下载"的路径（逐个 `@Transactional` 方法核对）。
- [ ] 手工验证：上传 → 触发分块 → `psql` 查到 `document_chunk` 与 `document_chunk_embedding` 行数一致且都属于最新 `revision`。

---

### Lane 前端

**目标**：实现知识库管理的完整操作界面，用户不需要碰任何 API 工具就能走完全流程。

**写入范围**
- `frontend/src/**`（需要先搭出路由、API 客户端、组件目录结构——当前 `App.tsx` 是唯一页面，没有任何这些）
- `frontend/package.json`（若需新增路由/请求库依赖）

**依赖**：产品 Lane 的 `ui-spec.md`（界面规则）；后端 Lane 的对应接口（可先按 [API 契约](../../../tech/knowledge-base/api.md) 并行开发，接口就绪后联调）。

**具体任务**
1. 基础设施先行：引入路由，建立 `api/` 客户端层（统一处理 `VITE_API_BASE_URL`、错误响应的 `{error, message}` 解包、分页响应形状），建立组件目录约定。**保留现有健康检查页**作为一个诊断页面，不要删掉。
2. 知识库列表页：列表、创建、改名、删除（二次确认弹层）。
3. 文档列表页：分页列表 + 状态过滤、本地文件上传、URL 来源添加（含定时同步配置表单）、启用/禁用、删除、触发分块。
4. 文档状态轮询：`RUNNING` 时按 2s 轮询文档详情，到达 `SUCCESS`/`FAILED` 后停止。**必须在组件卸载时清理定时器**。
5. 入库任务历史展示：`phase` 与 `status` 按产品 Lane 定义的中文表述展示，`SKIPPED` 不能显示成失败。
6. 分块管理页：按 `chunkIndex` 顺序分页展示、编辑内容、新增、删除、单条启用/禁用、批量启用/禁用（含选中数量与预期变化数量的反馈）。
7. 错误处理：413/415/429/409 四种错误必须有明确的用户提示，不能只显示"请求失败"——这四种恰好都是用户能自己解决的问题（文件太大、格式不支持、稍后再试、等处理完成）。

**完成定义**
- [ ] `cd frontend && pnpm build` 与 `pnpm lint` 通过。
- [ ] 在浏览器中实际完成一遍 §2 描述的可演示流程，不借助任何 API 调试工具。
- [ ] 上传超限文件、上传不支持格式、对处理中文档点删除，三种情况都能看到明确的中文错误提示。
- [ ] 轮询在页面切走后停止（浏览器 Network 面板确认无残留请求）。

---

### Lane 测试

**目标**：产出覆盖正常/边界/异常三类场景的测试矩阵，并把其中可自动化的部分落成代码。

**写入范围**
- `backend/src/test/java/com/app/knowledge/**`（新建）
- `tech/knowledge-base/test-matrix.md`（新建：完整测试用例矩阵，含无法自动化的手工验证项）

**不得写入**：`backend/src/test/java/com/app/ApplicationTests.java`（保持 `spring.flyway.enabled=false` 的快速上下文加载测试不变，需要真实表结构的测试另建类）。

**依赖**：后端 Lane 的对应实现。测试矩阵文档可以在实现之前就写好（先写用例再写实现，见下）。

**具体任务**
1. 先输出 `test-matrix.md`，**在后端实现之前完成**。矩阵按功能域组织，每条用例写清：前置状态、操作、预期结果、是否可自动化。
2. 单元测试（无需数据库）：两种分块策略的切分正确性（含空文档、单字符文档、恰好等于 `chunkSize`、`overlap` 边界）、token 启发式估算、cron 最短间隔校验。
3. 集成测试（需真实 Postgres，显式开启 Flyway）：
   - **逻辑删除一致性**——删除知识库/文档/分块后，所有列表与详情接口都不再返回它，且向量表对应行已物理删除。这组用例专门针对"`repository` 查询漏写 `deleted = false`"这个高风险点，每个查询方法都要有对应用例。
   - **CAS 并发保护**——文档处于 `RUNNING` 时，删除/更新/重复触发分块三种操作都返回 409。
   - **幂等**——重复禁用已禁用的文档、提交与库中完全相同的分块内容，都不产生任何写操作（用向量表行的 `create_time` 未变化来断言）。
   - **卡死回收**——手工把一条 `ingestion_run` 置为 `RUNNING` 且 `heartbeat_time` 设为 6 分钟前，验证回收任务将其标记 `FAILED`。
   - **`chunkIndex` 用 max+1 而非 count**——建 10 个分块、删掉第 5 和第 8 个、再新增一个，验证新分块序号是 10 而不是 8。
   - **批量接口约束**——超过 500 条报错、含无效 id 时整批失败、全部已是目标状态时返回 `changed: 0` 而不报错。
4. 手工验证项（写进矩阵但不自动化）：
   - **上传 30MB 文件时的堆内存峰值**——[技术方案 §7](../../../tech/knowledge-base/architecture.md) 明确要求实测验证 `RequestBody.fromFile` 是否存在学习笔记 03-03 记录的内存放大问题。用 `jcmd <pid> GC.heap_info` 或 JFR 观察。**这是本 Lane 唯一可能推翻技术方案的用例，优先做。**
   - 定时同步在源文件未变化时不触发重新分块（改一次源文件再观察一次，两次行为要不同）。
   - 并发上传超过 `max-concurrent` 时返回 429，且被拒绝的请求未产生临时文件。

**完成定义**
- [ ] `test-matrix.md` 覆盖 [PRD §8](../../prd/2026-07-25-knowledge-base-prd.md) 全部 7 条验收标准，每条能追溯到具体用例。
- [ ] `cd backend && ./mvnw -q -B verify` 通过，含新增的单元测试与集成测试。
- [ ] 全部手工验证项已执行并把**实际结果**记录进矩阵（包括与预期不符的情况——尤其是内存实测，若结果不达标必须回到技术方案改方案，不能默默放过）。

---

### Lane 运维

**目标**：让新增依赖和测试要求在 CI 中真正被执行，并把本模块引入的配置项完整登记到部署环境说明中。

**写入范围**
- `.github/workflows/ci.yml`
- `deploy/env/staging.env.example`、`deploy/env/production.env.example`
- `deploy/README.md`
- `scripts/ci-validate.sh`（仅在需要新增校验步骤时）
- `README.md`（若新增了开发者必须知道的启动前置条件）

**依赖**：后端 Lane 确定最终的配置项清单。

**具体任务**
1. 把 Embedding 相关配置登记进两份 env example（`EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` / `EMBEDDING_BATCH_SIZE`）以及上传限制、上传并发、定时同步配置。**`EMBEDDING_API_KEY` 是本项目第一个真实的密钥类配置**，env example 里必须是占位值，并在 `deploy/README.md` 说明它不能进仓库。
2. CI 需要能跑集成测试——当前 CI 已有 postgres/redis/rustfs 服务，确认 postgres 服务带 pgvector 扩展且 Flyway 迁移能在 CI 中执行。
3. **CI 中的 Embedding 调用问题**：集成测试若真的调用 Embedding API，会在每次 CI 中产生真实费用，且需要把密钥放进仓库 Secrets。决策：**CI 中注入一个确定性的假 `EmbeddingClient`**（返回固定维度的伪向量），真实 API 调用只在本地手工验证。这个决定要在 `deploy/README.md` 里写清楚，否则后来者会以为 CI 覆盖了真实链路。
4. 确认 `scripts/ci-validate.sh` 仍然全绿（它会跑文档链接检查，本轮新增的 `tech/` 目录需要纳入扫描范围）。

**完成定义**
- [ ] `scripts/ci-validate.sh` 本地全绿。
- [ ] CI 在 GitHub 上实际跑绿一次（包含新增的集成测试）。
- [ ] 两份 env example 包含本模块全部新增配置项，无真实密钥。
- [ ] `deploy/README.md` 说明了 `EMBEDDING_API_KEY` 的管理方式，以及 CI 用假 Embedding 实现这一事实。

---

## 5. Risks

| 风险 | 影响 | 应对 |
|---|---|---|
| `RequestBody.fromFile` 实测仍有内存放大 | 技术方案 §7 的上传方案需要改成预签名 URL，前端上传链路要跟着改 | 测试 Lane 把这项列为**最优先**的手工验证，在前端上传功能大量投入之前就做掉。退路（预签名方案）已在学习笔记 03-03 完整记录 |
| Embedding API Key 未就绪 | 端到端链路无法验证，只能验证到分块为止 | 后端 Lane 的假实现（未配置时抛明确异常）让应用仍能启动；集成测试用确定性假实现，不阻塞开发。**但需要用户尽早提供一个可用的 Key** |
| `repository` 层漏写 `deleted = false` | 已删除数据在某个查询路径上"复活"，是逻辑删除最典型的 bug，且不容易在开发时发现 | 测试 Lane 为每个查询方法安排逻辑删除用例；后端 Lane 完成定义里有逐方法核对项 |
| 事务内混入外部 IO | 长事务占满连接池，症状是整个应用在大文档处理时变慢，排查方向容易跑偏 | 后端 Lane 完成定义里有逐个 `@Transactional` 方法核对项；这是学习笔记里出现五次的同一个坑，已知必然会遇到 |
| 前端从零搭基础设施（路由/请求层/组件结构）耗时超预期 | 前端 Lane 阻塞 | 前端第一张工单就是基础设施，独立于任何业务功能，可以和后端并行；页面按知识库→文档→分块顺序推进，每完成一个都可独立演示 |
| Tika 依赖体积大幅增加构建产物 | 后端 jar 变大、CI 构建变慢 | 只引 `tika-core` + `tika-parsers-standard-package`，不引全量 `tika-app`。若确实过重，退化为 pdfbox + poi + 纯文本三个针对性依赖 |

## 6. Validation

三层验证，全部通过才算 Step D~F 完成：

```bash
# 1. 后端：编译 + 单元测试 + 集成测试
cd backend && ./mvnw -q -B verify

# 2. 前端：类型检查 + 构建 + lint
cd frontend && pnpm build && pnpm lint

# 3. 整体（等价于 CI 的 validate job：compose 配置 + 文档链接 + 前端构建 + 后端 verify）
scripts/ci-validate.sh
```

外加两项无法自动化的验收：
- 浏览器中完整走通 §2 的可演示流程。
- 测试 Lane 全部手工验证项已执行且结果记录在案。

## 7. Rollback

本模块是纯增量：新增 `com.app.knowledge` 包、新增 `V2` migration、新增前端页面，对现有代码只改 `application.properties` 和 `pom.xml`。

- 代码回滚：按工单粒度提交，任一工单有问题可单独回退。
- 数据库回滚：`V2` 只建新表，不改已有结构。需要重来时手工 `drop` 这 5 张表并删除 `flyway_schema_history` 中的 v2 记录，不写 down migration（Flyway 社区版不支持，且新建表的回滚是确定性的手工操作，不值得为它引入额外机制）。
- 设计层面回滚：若某项技术决策被验证推翻（最可能是上传内存方案），在 §8 Decision Log 记录实测结果，更新 [技术方案](../../../tech/knowledge-base/architecture.md) 与 [ADR 0002](../../adr/0002-knowledge-base-async-and-concurrency.md)，再改实现。**不允许代码偏离文档而不更新文档。**

## 8. Decision Log

- `2026-07-25`: 本任务书创建（Phase 1 Step C 产出）。PRD 经用户确认转为 approved，并追加"全系统业务数据统一逻辑删除"的全局约束（PRD §7.6），向量数据物理删除、对象存储原始文件保留是两项显式例外。PRD §9 的 4 项待决策事项全部在技术方案中决策：① 异步机制用数据库任务表 + 轮询，不引入 MQ；② 分块策略第一版支持固定长度 + 递归分隔符两种，语义分块推迟到 Phase 4；③ 不做数据库租约锁，改用状态 CAS + 启动回收 + 心跳超时回收；④ Embedding 定义最小接口，第一版走 OpenAI 兼容协议、供应商可配置。①③ 的等价性论证独立成 [ADR 0002](../../adr/0002-knowledge-base-async-and-concurrency.md)。技术方案另提出两项待用户确认的技术性收敛：向量用单表 + `kb_id` 过滤实现"每知识库独立 collection"（代价：不同知识库不能用不同维度模型）、文档版本用文档表自增号承载而不建独立表（代价：不保留历史版本内容、不能回滚版本）。
- `2026-07-25`: 用户确认上述两项技术性收敛均按推荐方案采纳，技术方案三件套转为 approved。同时选定 **Embedding 供应商为阿里云百炼 DashScope**（`text-embedding-v3`，1024 维，与 `vector(1024)` 直接对齐无需改 migration，国内网络可直连）。`app.embedding.base-url` 默认值定为 DashScope 的 OpenAI 兼容端点。工单 06 增加一条前置动作：查官方文档确认 `text-embedding-v3` 的单次请求条数上限再定 `batch-size` 默认值——超上限的症状是"大文档第一批就整体失败、小文档完全正常"，容易被误判成偶发问题。
  **待用户提供**：DashScope API Key。它只阻塞真实链路的端到端验证，不阻塞开发——工单 06 的假实现让应用在未配置 Key 时仍能启动，集成测试用确定性假实现。
