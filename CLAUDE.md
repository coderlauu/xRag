# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

xrag 目前同时在做两件事，不是一个已完工的产品：

1. **学习产出**：把知识星球专栏《Ragent AI》（作者“马丁”，配套开源仓库 [nageoffer/ragent](https://github.com/nageoffer/ragent)）的 91 篇正文，按系统模块重新分类整理为结构化学习文档，落在 `learning/ragent-column/`。
2. **系统建设**：以学习内容为输入，在本仓库（Spring Boot 后端 + React 前端）中独立设计并真实实现一套企业级 Agentic RAG 系统。

两条线交替推进：学完一组内容 → 为对应模块产出 PRD/技术方案/任务书 → 开发落地 → 测试/CI → 复盘对齐 → 再进入下一组。当前进度、阶段划分和检查点见 [docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md](docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md)。

**关键约束（ADR 0001）**：仓库历史中已删除的旧 TypeScript 实现、以及课程配套的 `nageoffer/ragent` 仓库，都**不是** xrag 的代码基线，禁止直接迁移或复制其 Schema、配置、模块结构；只能作为对比参考，且引用其结论时必须注明所检查的版本/commit。详见 [docs/adr/0001-build-xrag-independently.md](docs/adr/0001-build-xrag-independently.md)。

**当前实现状态**：后端只有健康检查接口，前端只有一个调用健康检查接口的占位页面；知识库、模型调度、问答链路、评测体系等业务模块（对应 `learning/ragent-column/03~06`）均未开始实现。不要假设 `CONTEXT.md` / `learning/` 中提到的领域概念已经有对应代码——先看 `backend/src` 实际内容再下结论。

## 常用命令

### 后端（Java 17 + Spring Boot 3.4.1 + Maven，`backend/`）

```bash
cd backend && ./mvnw spring-boot:run                             # 启动，默认监听 :3001（PORT 环境变量可覆盖）
cd backend && ./mvnw -q -B verify                                # 编译 + 跑全部测试，CI 用的就是这条
cd backend && ./mvnw test -Dtest=ApplicationTests                # 只跑单个测试类
cd backend && ./mvnw test -Dtest=ApplicationTests#contextLoads   # 只跑单个测试方法
```

`backend/mvnw` 已固定 Maven 版本（3.9.9），优先用它而不是本机全局 `mvn`。本机默认 JDK 不一定是 17（曾实测默认走 JDK 26），执行前可用 `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -version` 确认；`scripts/ci-validate.sh` 已经会自动尝试解析 JDK 17。

### 前端（React 19 + TypeScript + Vite + pnpm，`frontend/`）

```bash
cd frontend && pnpm install
cd frontend && pnpm dev       # 默认监听 :5173
cd frontend && pnpm build     # tsc -b && vite build
cd frontend && pnpm lint      # oxlint
```

前端通过 `VITE_API_BASE_URL` 环境变量指向后端地址。`package.json` 里没有配置任何测试脚本，不要假设存在 `pnpm test`。

### 基础设施（Docker Compose：Postgres+pgvector / Redis / RustFS）

```bash
docker compose up -d              # 启动 postgres + redis + rustfs
scripts/infra-check.sh            # 等待并逐项检查各依赖端口/健康状态
scripts/infra-down.sh             # 默认只停容器、保留数据卷；加 --wipe 才会删除数据卷
```

对象存储统一使用 `rustfs` 这个 compose 服务名（`docker-compose.yml`、`scripts/infra-up.sh`、`scripts/test-env-up.sh`、`scripts/infra-check.sh`、`.github/workflows/ci.yml` 已保持一致，健康检查探测 `http://localhost:9000/health`）。RustFS 镜像仍处 alpha 阶段，已知会偶发 `CreateBucket` 503，遇到时先查 [02-02-Docker本地中间件部署.md](learning/ragent-column/02-bootstrap-and-deployment/02-02-Docker本地中间件部署.md) 里记录的坑，不要默认是自己的配置错了。

### 整体校验（等价于 CI 的 `validate` job）

```bash
scripts/ci-validate.sh
# 依次执行：docker compose config 检查 → node scripts/check-doc-links.mjs → frontend build → backend mvn verify
```

`scripts/check-doc-links.mjs` 会校验 `AGENTS.md` / `CLAUDE.md` / `README.md` / `learning/` / `docs/` / `tech/` / `deploy/` 下所有 Markdown 的内部链接是否有效，新增或挪动文档后务必跑一次。注意 `.scratch/` 不在扫描范围内（工单是临时执行载体，不做链接保证）。

## 架构要点

- **后端包结构**：`com.app` 下有 `Application`、`health.HealthController`、`config.StorageConfig`、`config.FlywayConfig` 四个类；JDBC 直连（无 JPA/MyBatis）。已接入 Flyway，`db/migration/V1__enable_pgvector.sql` 只负责启用 pgvector 扩展，业务表待后续阶段以新增 migration 文件的形式添加。
- **启动不因依赖未就绪而崩溃**：Flyway 默认会在连不上 Postgres 时让整个应用启动失败，与本项目"应用始终能启动、依赖健康与否由 readiness 反映"的模式冲突，因此 `FlywayConfig` 自定义了 `FlywayMigrationStrategy`，迁移失败只记警告不阻塞启动；`StorageConfig.ensureStorageBucket` 同理。这意味着数据库/对象存储不可达时迁移和建 Bucket **不会自动补跑**——数据库恢复可达后需要重启应用才会真正执行迁移。`ApplicationTests` 额外用 `spring.flyway.enabled=false` 完全跳过，保持上下文加载测试更快、更不依赖外部状态。
- **Health/Readiness 模式**：`GET /api/v1/health` 恒定返回 200；`GET /api/v1/health/ready` 分别探测 Postgres/Redis/S3 三个依赖，任一失败即整体返回 503，响应体里逐项列出每个依赖的状态（见 `HealthController.checkDependency`）。新增外部依赖时应遵循同样的“独立探测、逐项上报”模式。
- **对象存储**：`StorageConfig` 用 AWS SDK v2 `S3Client`（path-style access）连接 RustFS，并在启动时通过 `ApplicationRunner` 幂等地检查/创建业务 Bucket——RustFS/MinIO 都不会在启动时自动建好 Bucket。
- **前端**：`App.tsx` 是唯一页面，仅 fetch 后端 `/api/v1/health` 展示状态；没有路由、状态管理或组件目录结构，后续模块开发前需要自行搭建。

## 文档体系（理解全局需要跨文件阅读）

- `docs/exec-plans/active/*.md`：在建计划，固定包含 Metadata/Objective/Scope/Risks/Plan/Validation/Rollback/Decision Log 章节；了解当前阶段状态先看这里，`completed/` 是归档。
- `docs/adr/`：已接受的架构决策。0001 独立建设（见上文约束）；0002 知识库入库的异步机制与并发控制——**不引入 RocketMQ/Redisson，用数据库任务表 + 状态字段 CAS + 启动回收/心跳超时替代事务消息、分布式锁、租约锁续期、分布式信号量**，每项都给了等价性论证，同时明确记录该决策依赖"单实例部署"假设及多实例下的失效点。模型 Provider、检索策略等尚未形成 ADR，遇到相关问题不要假设已有定论。
- `docs/prd/`：各模块产品需求文档。`2026-07-25-knowledge-base-prd.md` 已 approved，其 §7.6 是一条**全系统级**约束：所有业务数据的删除都是逻辑删除，两项例外是向量数据物理删除、对象存储原始文件保留。写任何模块的删除逻辑前先读这一节。
- `tech/<module>/`：各模块技术方案，固定三件套 `architecture.md` / `data-model.md` / `api.md`，必须同步维护。有界面的模块另加 `ui-spec.md`（界面规格，唯一权威）与 `test-matrix.md`（测试用例矩阵）。知识库模块的方案已完成，但**代码尚未开始实现**——`tech/knowledge-base/` 描述的是目标状态，不是当前状态。
- `prototype/<module>/index.html`：可点击交互原型，自包含单文件（无构建步骤、无外部依赖）。底部深色抽屉是演示控制台，用来切换那些"只在特定状态下才出现"的界面规则（处理中禁用、四种空态、错误提示）。规格冲突时以 `ui-spec.md` 为准并把原型改回来，见 [prototype/README.md](prototype/README.md)。
- `.scratch/<module>/issues/`：原子工单，按垂直切片组织并标注依赖边，附依赖图。`docs/exec-plans/active/*-implementation-lanes.md` 的 Lane 是职责视角（谁负责什么、完成定义是什么），工单是执行视角（实际推进顺序），同一批工作的两个切法，都要维护。
- [CONTEXT.md](CONTEXT.md)：项目根统一语言表，定义了知识库/源文档/文档版本/入库任务/文档分块/分块策略/向量配置版本/用户问题/检索表达/意图/检索通道/召回结果/回答/引用/对话/工具/工具调用/评测用例/链路记录等术语。设计模块、写 PRD 或代码注释时优先复用这套术语，不要自造同义词。
- `learning/ragent-column/`：知识星球专栏的重整理笔记，按目标系统模块分组（`00-overview`、`01-rag-fundamentals`、`02-bootstrap-and-deployment`、`03-knowledge-base`、`04-model-scheduling`、`05-qa-pipeline`、`06-evaluation`、`07-interview`），与原专栏教学顺序不同。`INDEX.md` 是唯一进度基线，`PHASE-0-CHECKPOINT.md` 记录了已验证的技术结论和进入 Phase 1 前识别出的脚手架缺口（上面“已知不一致”就来自这里）。
- `.agents/skills/` 与 `.claude/skills/`：同一份 mattpocock/skills 技能包的两份拷贝，`SKILLS_GUIDE.md` 说明了选择哪个 Skill 的判断流程（需求不清先 grill，需要跑代码验证设计用 prototype，跨会话任务先拆 spec/tickets，单会话直接实现用 tdd，改完用 code-review 收尾）。

## 产出物闸门（不可自行降级）

每个模块的 Step B 有两项产出物，**缺一不可，且都要在写前端代码之前交给用户确认**：

1. `tech/<module>/ui-spec.md`——界面规格（文字权威）。
2. `prototype/<module>/index.html`——可点击原型（交互确认载体）。

**只要模块有用户界面，原型就是必出项，不是可选项。** 计划文本里出现"若需要 / 可选 / 视情况"这类措辞时，判断权在用户手上——把选项和取舍摊开来问，不要替用户决定，也不要用"ASCII 线框已经说清了"来顶替原型。此前知识库模块就是这样把原型静默跳过的（原因见 [任务书 Decision Log](docs/exec-plans/active/2026-07-25-knowledge-base-implementation-lanes.md) `2026-07-25` 条目），修复是把原型写进了 Lane 产品的写入范围与完成定义。

理由：界面规则里最容易出错的部分（处理中该灰哪些按钮、`SKIPPED` 的措辞会不会被误读成失败、两种"分块为空"的区别）在 400 行文档里评审成本极高，看一眼页面却是秒判。原型改起来最便宜的时刻是 React 代码还不存在的时候。
