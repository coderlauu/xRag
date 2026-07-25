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
- `prototype/knowledge-base/index.html` + `prototype/README.md`（新建：可点击交互原型，自包含单文件。见[主计划 §6.1 产出物闸门](2026-07-24-ragent-column-learning-and-system-build.md)——**有界面的模块，原型是必出项**）

**依赖**：无，可立即开始。前端 Lane 依赖本 Lane 的 `ui-spec.md`，且**必须等原型经用户确认后才动手写页面**。

**具体任务**
1. 输出 `ui-spec.md`，覆盖三个页面：知识库列表页、文档列表页（含上传入口与定时同步配置）、分块管理页（子页面或抽屉）。每个页面写清：布局结构、每种数据状态下展示什么、哪些操作在什么条件下禁用。
2. 明确定义四种文档状态（`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`）在界面上的中文表述与视觉区分方式，以及 `RUNNING` 时哪些按钮必须禁用。
3. 明确 `ingestion_run` 的 `SKIPPED` 状态怎么向用户解释——它不是失败，是"检查过、内容没变、无需处理"，措辞不能让用户以为出了问题。
4. 明确 `phase` 字段（`DOWNLOAD`/`EXTRACT`/`CHUNK`/`EMBED`/`PERSIST`）的中文映射，让失败信息对用户有意义。
5. 定义批量启用/禁用的操作反馈文案（PRD §5 要求展示"已选中 N 个，其中 M 个将发生变化"）。
6. 产出可点击原型 `prototype/knowledge-base/index.html`，把 `ui-spec.md` 里"只在特定状态下才出现"的规则做成可切换的：文档四状态、五个 `phase`、四种空态、七种错误提示、三种同步结果。目的是让这些规则**可评审**——文档里核对文案很累，看页面是秒判。

**完成定义**
- [ ] `ui-spec.md` 三个页面全部有结构说明和状态规则，前端 Lane 确认无需再回来追问需求。
- [ ] 全部状态枚举、`phase` 枚举都有确定的中文表述，不在代码里临时决定。
- [ ] 原型已产出并**经用户确认交互**——这是前端 Lane 开工的前置闸门，不是可选步骤。
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
- `2026-07-25`: Step D 启动，完成前三张无阻塞工单 **01 / 02 / 03**。
  - **03（表结构与配置基线）**：`V2__knowledge_base_schema.sql` 建成 5 张表，Flyway 已实际迁移到 v2。逐项实测确认了七条断言，其中两条超出工单原有清单：`FILE` 来源开定时同步被 `ck_source_document_sync_fields` 拒绝、`URL` 来源缺 `source_uri` 被 `ck_source_document_url_fields` 拒绝——它们是 PRD §4.3 硬性边界在最底层的兜底，值得同时验掉。三处有意偏离工单清单：只建 `model` 一个子包（Java 空包无意义也不被 git 跟踪，分层纪律改写进 `com/app/knowledge/package-info.java`）、不加 `app.upload.acquire-timeout`（架构文档未要求它可配，留给工单 08 决定）、多建一个 `ChunkStrategy` 枚举（性质与其他五个一致）。
  - **01（界面规格）**：产出 [ui-spec.md](../../../tech/knowledge-base/ui-spec.md)。过程中发现一个 API 契约缺陷：`409 INVALID_STATE` 对应至少三种情况（给禁用文档触发分块 / 给禁用文档新增分块 / 启用分块但父文档禁用），前端从错误码上无法区分。**决定不拆错误码，改为约束后端 `message` 必须是可直接展示给用户的完整句子**——三者的修复动作是同一个（先启用文档），前端不需要按码分支，拆码只会多一层映射表要维护。已同步写进 [api.md](../../../tech/knowledge-base/api.md) §1。
  - **02（测试矩阵）**：产出 [test-matrix.md](../../../tech/knowledge-base/test-matrix.md)，8 个功能域 106 条用例 + 9 条手工验收项 + PRD §8 追溯表。最高优先级手工项 M-01（30MB 上传堆内存）的判定标准改成了**二元**的：用 `-Xmx256m` 跑，堆上限远小于"文件 30MB + SigV4 摘要放大"所需，能跑完即证明请求体没被整体读进堆，OOM 即失败——比对着 JFR 堆曲线目测可靠得多。
  - **顺带修掉一处文档 bug**：[architecture.md](../../../tech/knowledge-base/architecture.md) §3.4 把心跳列写成 `heartbeat_at`，实际 DDL 与其余四处文档都是 `heartbeat_time`。
  - `scripts/ci-validate.sh` 通过（compose 配置 + 文档链接 + 前端构建 + 后端 verify）。
  - **下一个前沿**：**04**（前端基础设施，阻塞项 01 已完成）、**06**（EmbeddingClient）、**09**（提取+分块），三者彼此无依赖可并行。
- `2026-07-25`: **补上被漏掉的交互原型。** 用户指出"原型好像不在计划内"——查证结果是**在计划内被静默跳过了**：主执行计划把"原型/交互说明"列为每模块必出的 SDLC 产出物，Step B 明确写作"产品侧任务书(PRD+原型)"，而批准的计划里"若需要可视化原型再用 Artifact 产出可点击 HTML 原型"这句的判断权在用户手上，被我替用户判了。`grep 原型` 在 PRD、技术方案、19 张工单里一次都搜不到，说明是整个丢掉而非有意识推迟。
  - 补的时机恰好：下一张工单是 04（前端基础设施），原型的全部价值就在于"React 代码还不存在时改起来最便宜"。
  - 产出 [prototype/knowledge-base/index.html](../../../prototype/knowledge-base/index.html)（自包含单文件，无构建步骤）+ [prototype/README.md](../../../prototype/README.md) 说明原型与 ui-spec 的分工（规格以 ui-spec 为准，原型是用来评审规格的）。
  - 关键设计是底部的**演示控制台**：可切换文档四种状态、处理阶段、文档启用状态、四种空态、七种错误提示、三种同步结果。它刻意做成"脚手架"观感以区别于产品。存在理由是让那些"只在特定状态下才出现"的规则变得可评审——`RUNNING` 时该灰的按钮、父文档禁用时"批量启用"灰而"批量禁用"不灰的不对称、两种不同原因的"分块为空"。这些状态在真实系统里要构造数据才看得到，评审时没人会为看一眼文案去手工造五种状态。
  - 顺带补了一处 ui-spec 的结构缺口：§1 只列了三条路由，§5 却引用了一个"文档详情页"。原型把它实现为文档列表上的**右侧抽屉**（不新增路由，且保留列表上下文），承载 `latestRun` / 处理记录 / `phase` 中文映射 / 定时同步块。
  - `scripts/check-doc-links.mjs` 的扫描根新增 `prototype/`。
- `2026-07-25`: **把原型从"补上的一次性动作"变成制度性闸门。** 用户要求"如果 Step 需要原型输出的场景务必不能跳过，需要输出原型让我确认交互"，并要求回头审计已完成的 Step。审计结论见[主计划 Decision Log](2026-07-24-ragent-column-learning-and-system-build.md) 同日条目——只有原型一项被真正静默跳过，其余产出物或已完成或尚未到期。本文件对应改动：
  - Lane 产品的**写入范围**加入 `prototype/knowledge-base/`，**完成定义**新增"原型经用户确认交互"一条；Lane 前端的依赖从"依赖 ui-spec"改为"必须等原型经用户确认后才动手写页面"。
  - 根因不在"忘了"，而在于原型从未进入本文件的写入范围：主计划只在一句话概述里提过它，下游的工单清单、Lane 完成定义因此全都自洽地不含它，任何自查都发现不了。修复的落点必须是**写入范围与完成定义**，只补一次产出物防不住 04~06 模块重犯。
  - 工单 01 状态从 `done` 退回"待用户确认原型"，工单 04 改为 `blocked-on-review`——真实状态就是这样，不能因为文件已经写出来了就记成完成。
- `2026-07-25`: Step D 第二批，工单 **04 / 06 / 09** 三张并行完成（原型确认闸门通过后 04 解锁）。后端 `./mvnw -B verify` 47 条测试全绿，前端 `pnpm build` + `pnpm lint` 通过。
  - **06（EmbeddingClient）**：查证确认 `batch-size = 10` 是 DashScope 的硬上限而非保守取值（官方文档 OpenAI 兼容一节：`text-embedding-v3`/`v4` 输入列表最多 10 条、单条 8,192 token），来源链接已写进配置注释与 [architecture.md §6](../../../tech/knowledge-base/architecture.md)。真实 Postgres 上实测三种启动路径：维度配 768 时启动失败并说明冲突、默认 1024 时校验通过、未配 Key 时正常启动只记警告。**维度不一致与数据库不可达被有意区别对待**——前者是确定性配置错误直接拦停，后者是环境状态只记警告，沿用既有模式。
  - **09（提取+分块）**：24 条单测，是本模块唯一能被完整覆盖的部分。**测试暴露了一个真 bug**：`RECURSIVE` 合并阶段保留重叠后，"重叠 + 下一片段"仍可能超过 `chunkSize`（实测切出 53 字符的块，上限 50）。修复确立了取舍——**超长是硬约束、重叠是锦上添花**，冲突时丢重叠。另修正了一条前提写错的测试：Tika 按内容而非扩展名探测类型，误命名为 `.pdf` 的纯文本会被正常提取（这是对的行为，格式白名单是上传接口的职责）。
  - **04（前端基础设施）**：路由 + `api/` 客户端层 + 加载/错误态组件。**做了工单清单之外的一件事**：配了 Vite 开发期同源代理。浏览器实测发现 `:5173` 直连后端是跨源请求、被浏览器拦掉，界面显示"无法连接到服务器"——看起来像后端没启动，实际是同源策略。README 原本就写着"本地开发默认同源代理需自行配置"，这张工单是配它的时机。选代理而非给后端加 CORS：代理只影响开发期，CORS 是生产形态决定，该走运维 Lane。
  - ui-spec §7 补入两行兜底文案（网络失败 / 未知错误），实现里的文案与规格逐字一致。
  - **发现一处待修的文档不一致（不阻塞已完成工单，但阻塞工单 07/12）**：[api.md](../../../tech/knowledge-base/api.md) 的上传接口接受 `chunkStrategy` / `chunkSize` / `overlap` 且文档响应体回传 `chunkStrategy` / `chunkConfig`，示例里不同文档用了不同取值（1000/100 与 800/80），说明设计意图是**每文档可配**；但 `source_document` 表与 [data-model.md §3.2](../../../tech/knowledge-base/data-model.md) 都没有对应的列。定时同步重新分块时需要记得原策略，所以列是必要的。**修复留给工单 07**（它实现上传接口，是真正需要这三列的地方），预计以 `V3` migration 增列而非改 `V2`——`V2` 已在本地实际执行过，改它会触发 Flyway 校验和不一致，而 Flyway 的价值正是已执行迁移不可变。
  - **仍待用户提供 DashScope API Key**：只阻塞工单 06 的真实链路手工验证（测试矩阵 M-06），不阻塞任何开发。
- `2026-07-25`: Step D 第三批，工单 **05 / 07** 完成。**最高优先级风险已解除**。
  - **M-01 内存实测通过，技术方案保持不变，不退回预签名 URL 方案。** 执行时把文件从工单写的 30MB 加到 **48MB**（50MB 上限内的最大值）——30MB 按学习笔记 03-03 的约 3.3 倍放大也只有 ~100MB，仍装得进 256MB 堆，"没 OOM"证明不了什么。结论证据不是"没 OOM"，而是**堆 committed 全程停在 61.4MB 一次都没扩张**（上传前 used 38.7MB，连续 4 次 48MB 上传峰值 51.4MB）：JVM 连要更多堆的必要都没有。对象存储侧核对过实际落盘字节数，内容完整。完整数据记入 [test-matrix.md §9 M-01](../../../tech/knowledge-base/test-matrix.md)。§5 风险表第一行可以关闭了。
  - **补上了 `source_document` 缺失的分块配置三列**（上一条 Decision Log 里识别出的不一致）。用 `V3__source_document_chunk_config.sql` 增列而非改 `V2`：V2 已实际执行过，改它会破坏 Flyway 校验和，已执行迁移不可变正是 Flyway 的价值。[data-model.md §3.2](../../../tech/knowledge-base/data-model.md) 已同步。
  - **两处越界改动，都记在明处**：① 给 CI `validate` job 加了 Postgres 服务——那是工单 19（运维 Lane）的写入范围，但不加的话本批的集成测试推上去 CI 必红（`validate` 跑 `mvn verify` 却没有任何数据库）。加的是最小版本，19 接手时应把它当起点而非终点。② 加了 `/knowledge-bases/:kbId` 占位页——知识库列表已经链过来了，留 404 比留"尚未实现"更糟。
  - **`MaxUploadSizeExceededException` 必须显式处理**，否则 413 返回容器默认错误页而不是契约体。它在控制器方法之前由 multipart 解析器抛出，因此 `@RestControllerAdvice` 不能限定 `basePackages`——那时 handler 还没解析出来。实测 55MB 文件返回的是契约体。
  - 后端累计 **66 条测试**全绿（含 19 条需真实 Postgres + RustFS 的集成测试），前端 `pnpm build` / `pnpm lint` 通过，浏览器走通"创建知识库 → 改名 → 上传文件 → 列表显示待处理 → 415 错误提示 → 删除知识库回到空态"。
  - **下一个前沿：08（上传并发限流）、10（异步入库全链路）**。工单 10 是第一个真正需要 Embedding 的环节，届时**没有 DashScope API Key 就只能验证到分块为止**。
- `2026-07-25`: **Embedding 供应商由阿里云百炼改为火山方舟 Ark**（用户提供 Key，外部阻塞项解除）。这不是偏好调整，是 Key 的套餐决定的：手上是 **Coding Plan** Key，实测 `doubao-embedding-large` / `doubao-embedding` / `doubao-embedding-text-240715` 全部返回 *"The requested model does not support the agent plan feature"*，`doubao-embedding-vision` 是唯一可用的向量模型（多模态模型，纯文本输入工作正常）。
  - 四点实测确认，每点都容易被"顺手改错"，已写进 [architecture.md §6](../../../tech/knowledge-base/architecture.md) 与配置注释：① `base-url` 是 **`/api/plan/v3`** 而非常见的 `/api/v3`，Plan 类 Key 打标准路径直接 401，看着像笔误其实不是；② 模型**原生输出 2048 维**，1024 靠请求里的 `dimensions` 参数降维得到；③ `batch-size` 上限实测为 10（传 32 条被拒）；④ 无需改 migration，`vector(1024)` 保持不变。
  - **②让"`dimensions` 始终显式发送"这条早先的决定真正兑现了价值**：去掉这个参数会拿到 2048 维向量、每次写库都在运行时报错，而启动时的维度校验拦不住它——校验比的是"配置 vs 建表"，两边都是 1024、都对，错的是请求少发了一个字段。当时选择"始终显式发送而不是只在非默认时发送"是为了让宽度不匹配立刻以 400 暴露，现在正好用上。
  - **一个影响测试写法的发现：模型输出不是逐位确定的。** 同一段文本两次调用向量有 1e-3 量级抖动（大概率低精度推理）。任何拿真实 API 断言"向量相等"的测试都会 flaky——`RealEmbeddingApiTests` 改用余弦相似度（同文本 `>0.999`、邻近文本 `<0.999`，后者才是真正要防的分批错位）。这也顺带强化了 architecture.md §3.5「内容没变就不重算向量」的理由：不只是省钱，也避免同样内容在库里反复得到略微不同的向量。
  - 真实 API 验证（测试矩阵 M-06）落成 `RealEmbeddingApiTests`，用 `@EnabledIfEnvironmentVariable` 守门，无 Key 自动跳过。**这个取舍必须写进 `deploy/README.md`（工单 19）**，否则后来者会以为 CI 覆盖了真实 API——CI 用的是确定性假实现。
  - **密钥处置**：Key 只经环境变量注入，未写入仓库任何文件。已提醒用户该 Key 已出现在对话记录中，建议轮换。
  - 后端 68 条测试全绿（无 Key 时 2 条真实 API 用例自动跳过）。
- `2026-07-25`: 工单 **10（异步入库全链路）** 完成，**模块核心路径打通**：上传 → 触发 → 提取 → 切分 → 真实向量化 → 写库，浏览器里可完整演示。
  - **写代码时当场避开了一个会静默失效的坑**：`executeSteps` 调同类的 `persist`，**自调用走不到 Spring 代理，`@Transactional` 会静默失效**。后果不是报错，是第 5 步的五条写入各自独立提交，中途失败留下"旧分块删了、新分块只插了一半、向量对不上"的残局，且没有任何迹象提示事务没生效。改用编程式事务（`TransactionTemplate`），同时让"事务从哪开始到哪结束"在调用点直接可见——这正是本模块最在意的纪律。`markFailed` 同理，用 `PROPAGATION_REQUIRES_NEW` 的独立模板。
  - **真实 Ark API 端到端实测**：601 字符文档 → 1 分块 = 1 向量，`vector_dims` 实测 1024；25 段文档（chunkSize 60）→ 25 分块 = 25 向量、维度种类只有 1 种，**跨 3 批真实调用**（10+10+5），分批与顺序都对。
  - 界面规则逐条核对：状态「处理中」、阶段提示「正在计算向量」（证明轮询在拉 `latestRun.phase`）、触发按钮禁用且 tooltip 与后端 409 的 message 逐字一致。轮询停止用**应用内路由跳转**验证（不是整页刷新——那会重置 performance 时间线，什么都证明不了），切走后只多 1 次在途请求；实测轮询间隔精确 2000ms。
  - **修掉一个自己引入的 flaky 测试，根因值得记**：入库测试首跑 4 条随机失败（期望 `SUCCESS` 实际 `FAILED`）。原因是 `IngestionIntegrationTests` 用了内嵌的 `@TestConfiguration`，导入集合与其他测试类不同 → **Spring 另起了一个上下文，而旧上下文的 `IngestionDispatcher` 不会停**。两个调度器同时活着时，新上下文的启动回收把旧上下文正在跑的任务判成僵尸标记为 FAILED。修复是把假 Embedding 抽成共享的 `FakeEmbeddingConfig`，三个集成测试类统一导入，保证只有一个上下文。
    **这恰好演示了 [ADR 0002](../../adr/0002-knowledge-base-async-and-concurrency.md) 里"启动回收依赖单实例假设"一旦被破坏会怎样**——症状不是报错，是别人的任务被莫名其妙杀掉。生产是单实例不受影响，但多实例部署前必须先改掉这一层，ADR 里的警告不是形式主义。
  - **心跳独立于执行线程**（单独的 `ScheduledExecutorService`）：执行线程正卡在下载或 Embedding 上时心跳也得继续跳，否则心跳超时回收会把还活着的任务误判成卡死。
  - `ingestion_run` 的查询**没有 `deleted = false`**——它是日志性质的记录，没有"删除"操作。这是有意的，写进了类注释，免得后来者当成漏写补上去。
