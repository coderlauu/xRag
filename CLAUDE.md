# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

xrag 目前同时在做两件事，不是一个已完工的产品：

1. **学习产出**：把知识星球专栏《Ragent AI》（作者“马丁”，配套开源仓库 [nageoffer/ragent](https://github.com/nageoffer/ragent)）的 91 篇正文，按系统模块重新分类整理为结构化学习文档，落在 `learning/ragent-column/`。
2. **系统建设**：以学习内容为输入，在本仓库（Spring Boot 后端 + React 前端）中独立设计并真实实现一套企业级 Agentic RAG 系统。

两条线交替推进：学完一组内容 → 为对应模块产出 PRD/技术方案/任务书 → 开发落地 → 测试/CI → 复盘对齐 → 再进入下一组。

**关键约束（ADR 0001）**：仓库历史中已删除的旧 TypeScript 实现、以及课程配套的 `nageoffer/ragent` 仓库，都**不是** xrag 的代码基线，禁止直接迁移或复制其 Schema、配置、模块结构；只能作为对比参考，且引用其结论时必须注明所检查的版本/commit。详见 [docs/adr/0001-build-xrag-independently.md](docs/adr/0001-build-xrag-independently.md)。

**当前进度的真相源不在本文件**：阶段状态看 [主计划 §6.0](docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md)，模块完成度看 `docs/status/` 下最新的里程碑。粗线条上：知识库（`com.app.knowledge`）与模型调度（`com.app.ai`）已有真实实现且有集成测试覆盖，问答链路与评测体系（`learning/ragent-column/05~06`）尚无任何代码。要判断某个领域概念是否已落地，看 `backend/src/main/java/com/app/` 下有没有对应包，比读本文件可靠。

## 常用命令

### 后端（Java 17 + Spring Boot 3.4.1 + Maven，`backend/`）

```bash
cd backend && ./mvnw spring-boot:run                             # 启动，默认监听 :3001（PORT 环境变量可覆盖）
cd backend && ./mvnw -q -B verify                                # 编译 + 跑全部测试，CI 用的就是这条
cd backend && ./mvnw test -Dtest=ApplicationTests                # 只跑单个测试类
cd backend && ./mvnw test -Dtest=ApplicationTests#contextLoads   # 只跑单个测试方法
```

> **跑测试前先停掉本机正在运行的后端**（尤其是 IDE 里启动的那个，它不在终端里，最容易被忘掉）。集成测试与 dev 实例连同一个库，两个 `IngestionDispatcher` 会互抢任务表，症状是成片的 “expected SUCCESS but was FAILED” 超时——**看起来完全像业务代码坏了，实际只是环境里多了一个进程**。根因是 [ADR 0002](docs/adr/0002-knowledge-base-async-and-concurrency.md) 的单实例假设。`scripts/ci-validate.sh` 内置了 3001 端口 preflight，但直接跑 `./mvnw` 会绕过它。

集成测试需要 Postgres/Redis/RustFS 就位，先跑 `scripts/test-env-up.sh`。`backend/mvnw` 已固定 Maven 版本（3.9.9），优先用它而不是本机全局 `mvn`。本机默认 JDK 不一定是 17（曾实测默认走 JDK 26），必要时 `JAVA_HOME=$(/usr/libexec/java_home -v 17)`；`scripts/ci-validate.sh` 会自动尝试解析 JDK 17。

### 前端（React 19 + TypeScript + Vite + pnpm，`frontend/`）

```bash
cd frontend && pnpm install
cd frontend && pnpm dev       # 默认监听 :5173
cd frontend && pnpm build     # tsc -b && vite build
cd frontend && pnpm lint      # oxlint
cd frontend && pnpm test      # vitest run
```

**本地不需要配 `VITE_API_BASE_URL`**：`frontend/vite.config.ts` 已把 `/api` 同源代理到 `BACKEND_ORIGIN`（默认 `http://localhost:3001`）。没有这个代理时 `:5173` 直连 `:3001` 是跨源请求，后端未配 CORS 会被浏览器拦掉，界面上表现为“无法连接到服务器”，很容易误判成后端没起来。该变量只留给“前端产物直连另一个域名的后端”这种部署形态。前端测试栈是 vitest + testing-library（2026-08-07 接入），配置在 `vite.config.ts` 的 `test` 段，setup 在 `src/test/setup.ts`，测试文件与被测组件同目录；`pnpm test` 已经是 `scripts/ci-validate.sh` 的一环。**写 UI 测试时 mock 全局 `fetch`，不要 mock `src/api/` 下的导出函数**——那层正是"请求实际带了哪些查询参数"的被测对象，mock 掉它等于把要验的逻辑一起 mock 掉了（理由见 [test-matrix §14.6](tech/model-scheduling/test-matrix.md)）。

### 基础设施（Docker Compose：Postgres+pgvector / Redis / RustFS）

```bash
scripts/infra-up.sh               # 起 postgres + redis + rustfs 并逐项检查
scripts/infra-check.sh            # 只做检查
scripts/infra-down.sh             # 默认只停容器、保留数据卷；加 --wipe 才会删除数据卷
```

对象存储统一使用 `rustfs` 这个 compose 服务名（`docker-compose.yml`、`scripts/infra-up.sh`、`scripts/test-env-up.sh`、`scripts/infra-check.sh`、`.github/workflows/ci.yml` 已保持一致，健康检查探测 `http://localhost:9000/health`）。RustFS 镜像仍处 alpha 阶段，已知会偶发 `CreateBucket` 503，遇到时先查 [02-02-Docker本地中间件部署.md](learning/ragent-column/02-bootstrap-and-deployment/02-02-Docker本地中间件部署.md) 里记录的坑，不要默认是自己的配置错了。

### 模型密钥

模型目录在 `backend/src/main/resources/ai-models.yml`（只读的路由规则与密钥**属性名**，密钥值只由环境注入，绝不进仓库）。**没有 Key 应用照常启动**，知识库的上传、分块、浏览、管理都能用；只有“要写向量”的操作会返回 `502 EMBEDDING_FAILED`。Embedding 维度必须与数据库 `vector(1024)` 一致，不一致时应用**直接启动失败**并说明冲突——这是有意的，放行只会推迟到每次写向量时才在运行时报错。供应商切换、合规风险与完整变量清单见 [deploy/README.md](deploy/README.md)。

### 整体校验（等价于 CI 的 `validate` job）

```bash
scripts/ci-validate.sh
```

依次执行：`docker compose config` → `check-doc-links.mjs` → `check-deploy-contract.mjs` → `node --test publish-feishu-docs.test.mjs` → `bash -n` 校验 lark skill 脚本 → frontend `build` + `lint` + `test` → 3001 端口 preflight → backend `mvnw verify`。

`scripts/check-doc-links.mjs` 校验 `AGENTS.md` / `CLAUDE.md` / `README.md` 以及 `learning/` / `docs/` / `tech/` / `prototype/` / `deploy/` 下所有 Markdown 的内部链接，新增或挪动文档后务必跑一次；`.scratch/` 不在扫描范围内（工单是临时执行载体，不做链接保证）。`.github/workflows/ci.yml` 除 `validate` 外还有 `infra`、`build-images`、`deploy-staging`、`smoke-staging`、`deploy-production`、`smoke-production` 六个 job，本地脚本只等价于第一个。

## 架构要点

- **后端包结构**：`com.app` 下 `ai`（大模型调度深模块）、`knowledge`（知识库）、`web`（`ApiExceptionHandler` 统一错误响应体，只翻译显式抛出的 `ApiException`，未预期异常照常走 500，不兜底成笼统错误码）、`config`、`health`。JdbcTemplate 直连，无 JPA/MyBatis；Flyway migration 已到 V6。**改任一业务包之前先读该包的 `package-info.java`**——分层职责和必须遵守的纪律写在那里，不在本文件。
- **启动不因依赖未就绪而崩溃**：`FlywayConfig` 自定义了 `FlywayMigrationStrategy`，迁移失败只记警告不阻塞启动；`StorageConfig.ensureStorageBucket` 同理。代价是数据库/对象存储不可达时**不会自动补跑**——恢复可达后需要重启应用。`ApplicationTests` 另用 `spring.flyway.enabled=false` 完全跳过。
- **Health/Readiness 模式**：`GET /api/v1/health` 恒定返回 200；`GET /api/v1/health/ready` 分别探测 Postgres/Redis/S3，任一失败即整体 503，响应体里逐项列出每个依赖的状态（见 `HealthController.checkDependency`）。新增外部依赖时遵循同样的“独立探测、逐项上报”模式。
- **对象存储**：`StorageConfig` 用 AWS SDK v2 `S3Client`（path-style access）连接 RustFS，启动时通过 `ApplicationRunner` 幂等建 Bucket——RustFS/MinIO 都不会自动建好。`ObjectStorageMaintenance` 默认只做 dry-run 审计，只有显式开 `STORAGE_MAINTENANCE_CLEANUP_ENABLED` 才真删对象。
- **跨模块纪律**（三条，写任何模块都适用）：① 全系统逻辑删除，repository 的查询方法必须带 `deleted = false`（知识库 PRD §7.6，是全系统级约束）；② 事务边界只允许出现在 service 层，且事务内不得有任何外部 IO（对象存储、HTTP 调用、远程下载）；③ 调度类组件（入库派发、定时同步、诊断保留）都带 `enabled` 开关，因为 ADR 0002 假设单实例，多个上下文连同一个库会互抢任务、互判僵尸。
- **模型调度的边界**：业务代码只能依赖 `com.app.ai.model` 下的能力端口（`ChatClient` / `StreamingChatClient` / `EmbeddingClient` / `RerankClient`）与不可变领域类型，**不得触碰 Provider SDK、调度内部态或供应商错误结构**；诊断写入是 best-effort，失败不改变模型调用结果。设计依据见 ADR [0003](docs/adr/0003-separate-model-capability-ports.md) / [0004](docs/adr/0004-reactive-streaming-runtime.md) / [0005](docs/adr/0005-separate-model-runtime-state.md) 与 [tech/model-scheduling/architecture.md](tech/model-scheduling/architecture.md)。
- **前端**：`App.tsx` 是路由表，页面在 `pages/`（知识库列表 / 文档列表 / 分块管理 / `/diagnostics` Trace Console）。`api/` 是访问后端的唯一入口，`client.ts` 统一 `/api/v1` 前缀、查询串拼装与错误映射；`apiUrl()` 专供**不该走 fetch** 的场景（几十 MB 的源文件交给浏览器直接打开，流式且自带进度）。

## 文档体系（理解全局需要跨文件阅读）

- `docs/exec-plans/active/*.md`：在建计划，固定包含 Metadata/Objective/Scope/Risks/Plan/Validation/Rollback/Decision Log 章节；了解当前阶段状态先看这里，`completed/` 是归档。
- `docs/adr/`：已接受的架构决策。0001 独立建设（见上文）；0002 知识库入库的异步机制与并发控制——**不引入 RocketMQ/Redisson，用数据库任务表 + 状态字段 CAS + 启动回收/心跳超时替代事务消息、分布式锁、租约锁续期、分布式信号量**，每项都给了等价性论证，同时明确记录该决策依赖“单实例部署”假设及多实例下的失效点；0003/0004/0005 覆盖模型调度（按能力分离端口、WebClient+Reactor 流式运行时、分离模型配置与熔断运行态与诊断历史）。检索策略尚未形成 ADR，遇到相关问题不要假设已有定论。
- `docs/prd/`：各模块产品需求文档。[知识库 PRD](docs/prd/2026-07-25-knowledge-base-prd.md) §7.6 是一条**全系统级**约束：所有业务数据的删除都是逻辑删除，两项例外是向量数据物理删除、对象存储原始文件保留。写任何模块的删除逻辑前先读这一节。另有 [模型调度 PRD](docs/prd/2026-07-28-model-scheduling-prd.md)。
- `docs/status/` / `docs/retro/` / `docs/research/`：阶段里程碑状态、阶段复盘、一手资料核查记录。判断“这个阶段做完了没、当时踩了什么坑、某个外部结论查证于哪一天”看这三处。
- `tech/<module>/`：各模块技术方案，固定三件套 `architecture.md` / `data-model.md` / `api.md`，必须同步维护。有界面的模块另加 `ui-spec.md`（界面规格，唯一权威）与 `test-matrix.md`（测试用例矩阵）。`knowledge-base/` 与 `model-scheduling/` 均已有对应实现；**它们仍是权威规格**，代码与文档冲突时以文档为准并修代码，不要反过来把文档改成迁就实现。
- `prototype/<module>/index.html`：可点击交互原型，自包含单文件（无构建步骤、无外部依赖）。底部深色抽屉是演示控制台，用来切换那些“只在特定状态下才出现”的界面规则（处理中禁用、空态、错误提示）。规格冲突时以 `ui-spec.md` 为准并把原型改回来，见 [prototype/README.md](prototype/README.md)。
- `.scratch/<module>/issues/`：原子工单，按垂直切片组织并标注依赖边，附依赖图（现有 `knowledge-base/` 与 `model-scheduling/`）。`docs/exec-plans/active/*-implementation-lanes.md` 的 Lane 是职责视角（谁负责什么、完成定义是什么），工单是执行视角（实际推进顺序），同一批工作的两个切法，都要维护。
- [CONTEXT.md](CONTEXT.md)：项目根统一语言表，定义了知识库/源文档/文档版本/入库任务/文档分块/分块策略/向量配置版本/用户问题/检索表达/意图/检索通道/召回结果/回答/引用/对话/工具/工具调用/评测用例/链路记录等术语。设计模块、写 PRD 或代码注释时优先复用这套术语，不要自造同义词。
- `learning/ragent-column/`：知识星球专栏的重整理笔记，按目标系统模块分组（`00-overview`、`01-rag-fundamentals`、`02-bootstrap-and-deployment`、`03-knowledge-base`、`04-model-scheduling`、`05-qa-pipeline`、`06-evaluation`、`07-interview`），与原专栏教学顺序不同。`INDEX.md` 是唯一进度基线，`PHASE-0-CHECKPOINT.md` 记录了已验证的技术结论与脚手架缺口。
- `.claude/skills/` 是 mattpocock/skills 技能包；`.agents/skills/` 是同一份再加项目自有的 `lark-sync-project-docs`（飞书文档同步，其 `scripts/sync.sh` 被 `ci-validate.sh` 做 `bash -n` 语法检查，改动后要重跑校验）。`SKILLS_GUIDE.md` 说明了选择哪个 Skill 的判断流程（需求不清先 grill，需要跑代码验证设计用 prototype，跨会话任务先拆 spec/tickets，单会话直接实现用 tdd，改完用 code-review 收尾）。

## 产出物闸门（不可自行降级）

每个模块的 Step B 有两项产出物，**缺一不可，且都要在写前端代码之前交给用户确认**：

1. `tech/<module>/ui-spec.md`——界面规格（文字权威）。
2. `prototype/<module>/index.html`——可点击原型（交互确认载体）。

**只要模块有用户界面，原型就是必出项，不是可选项。** 计划文本里出现"若需要 / 可选 / 视情况"这类措辞时，判断权在用户手上——把选项和取舍摊开来问，不要替用户决定，也不要用"ASCII 线框已经说清了"来顶替原型。此前知识库模块就是这样把原型静默跳过的（原因见 [任务书 Decision Log](docs/exec-plans/completed/2026-07-25-knowledge-base-implementation-lanes.md) `2026-07-25` 条目），修复是把原型写进了 Lane 产品的写入范围与完成定义。

理由：界面规则里最容易出错的部分（处理中该灰哪些按钮、`SKIPPED` 的措辞会不会被误读成失败、两种"分块为空"的区别）在 400 行文档里评审成本极高，看一眼页面却是秒判。原型改起来最便宜的时刻是 React 代码还不存在的时候。
