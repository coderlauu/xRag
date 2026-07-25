# Exec Plan: 知识星球《Ragent AI》专栏学习 + xrag 企业级 RAG 系统搭建

## 1. Metadata

- `plan_id`: 2026-07-24-ragent-column-learning-and-system-build
- `status`: active
- `owner`: liuqiang（本人学习+开发，AI 协作执行）
- `related_docs`: `learning/ragent-column/INDEX.md`、`learning/ragent-column/PHASE-0-CHECKPOINT.md`、`CONTEXT.md`、`docs/adr/*`、各 `docs/prd/*`、各 `docs/exec-plans/active/*-implementation-lanes.md`

## 2. Objective

以知识星球专栏《Ragent AI》（当前索引登记 91 篇正文，另有 1 个加群链接；作者"马丁"，配套开源仓库 [nageoffer/ragent](https://github.com/nageoffer/ragent)）为学习材料，AI 辅助深度阅读、重新分类整理成结构化学习文档，并据此在当前 `xrag` 仓库（Java/Spring Boot + React 空白脚手架）中真正设计、实现一套企业级 Agentic RAG 系统，同时产出可作为作品集/简历材料的完整企业级 SDLC 文档（产品/前端/后端/测试/运维分角色任务书）。

## 3. Scope

### In Scope

- 知识星球专栏当前索引登记的 91 篇正文的深度学习与重新分类整理，产出 `learning/ragent-column/` 学习文档，兼具"AI 模型交接说明书"作用。
- 按学习内容分组，对应设计并实现系统模块（知识库建设、大模型调度引擎、知识问答核心链路、RAG评测体系等），在 `xrag` 仓库中真实开发落地（后端 Spring Boot + 前端 React）。
- 每个模块产出完整企业级 SDLC 文档，清单与落位见 §6.1 产出物闸门：PRD、界面规格、**可点击交互原型**、技术方案三件套、分角色任务书、原子工单、测试用例矩阵、CI/CD 流水线设计、复盘与里程碑状态。
- 领域术语与架构决策沉淀进根目录 `CONTEXT.md` 与 `docs/adr/`（ADR）；二者均按需创建，不恢复空目录骨架。
- 最终汇总产出顶层「系统说明书」。

### Out Of Scope

- 不对已删除的旧版 TypeScript 实现做迁移或恢复，仅作为设计参考对比（用户明确决策）。
- 不修改知识星球平台本身内容，仅只读学习。
- 暂不涉及生产环境真实上线（域名/密钥仍为占位值），CI/CD 设计以流水线本身的正确性为目标，非实际生产发布。

## 4. Assumptions

- 知识星球专栏内容复用用户已授权的浏览器登录态只读访问；后续不以要求用户重复登录作为默认处理方式。
- 用户愿意配合分阶段的高频对齐检查点（每个内容分组学习完成后、每个系统模块开发完成后）。
- 参考策略：主线跟随专栏课程 + `nageoffer/ragent` 开源代码；旧 TS 实现仅偶尔对比参考。
- 任务书定位为"还原企业级全流程、可作品集展示"，因此文档详细度高于一般学习笔记。

## 5. Risks

- 浏览器登录态或平台访问方式可能变化，导致抓取中断——发生时先保留已完成进度并排查可复用会话或其他只读访问方式，不要求用户重复登录。
- 91 篇正文体量大，逐篇深度学习+产出企业级文档的总工作量很大，需要严格分阶段推进并在每个检查点确认，避免方向跑偏后大量返工。
- 专栏内容仍在持续更新（"面试中高频考点"分组标注"持续连载中"），最终分类和系统说明书可能需要在后续追更时调整。
- 原分类与我重新设计的系统模块分类之间可能存在文章内容跨模块的情况，需要在实际阅读后动态微调分组边界。

## 6. Plan

早期详细规划保存在 `~/.claude/plans/https-wx-zsxq-com-columns-51121244585524-transient-possum.md`（已获用户批准）；其中文章数和目录骨架是早期估算，执行范围与状态以本计划和 `learning/ragent-column/INDEX.md` 为准。核心阶段：

1. **Phase 0**：搭建 `learning/ragent-column/` 工作区骨架；抓取整理"项目概览与学习指南"(5篇)+"RAG与大模型基础概念"(25篇)+"本地开发与模型部署环境"(10篇)，合计 40 篇；提炼必要的 ADR/CONTEXT.md；环境实操验证；仅在形成首批真实工单时初始化 `.scratch/`。→ [对齐检查点](../../../learning/ragent-column/PHASE-0-CHECKPOINT.md)。
2. **Phase 1～4**（每个分组重复一次循环）：内容学习 → 产品侧任务书(PRD+界面规格+可点击原型) → 技术方案+分角色任务书(exec-plan Lanes) → 开发落地(前后端) → 测试与验收 → 运维/CI-CD → 复盘与状态更新 → 对齐检查点。依次覆盖：AI知识库建设 → 大模型调度引擎 → AI知识问答核心链路 → RAG评测体系。
3. **Phase 5**：消化"面试高频考点"分组；汇总全部阶段文档产出顶层系统说明书。

### 6.1 每模块产出物闸门

下表是 Phase 1～4 每个模块的必出产出物。**"用户确认"列为"是"的，必须经用户确认后才能进入下一个 Step**，不允许由 AI 自行判断"这个模块不需要"而跳过——`原型` 一项此前在知识库模块被这样静默跳过过，根因是它只出现在本计划的一句话概述里，从未落进任务书的写入范围与完成定义，于是下游工单、完成定义全都自洽地不含它，任何检查都发现不了。修复办法就是本表 + 任务书 Lane 产品的写入范围硬性包含 `prototype/<module>/`。

| Step | 产出物 | 落位 | 用户确认 |
|---|---|---|---|
| A | 学习整理稿 + 分组小结 | `learning/ragent-column/0N-*/` | 是（分组检查点） |
| B | PRD | `docs/prd/YYYY-MM-DD-<module>-prd.md` | 是 |
| B | 界面规格（有界面的模块） | `tech/<module>/ui-spec.md` | 随原型一并确认 |
| B | **可点击交互原型**（有界面的模块） | `prototype/<module>/index.html` | **是——写前端代码之前** |
| C | 技术方案三件套 | `tech/<module>/{architecture,data-model,api}.md` | 是（含技术性收敛的取舍） |
| C | 关键决策 ADR（偏离课程方案时） | `docs/adr/NNNN-*.md` | 随技术方案一并确认 |
| C | 分角色任务书 | `docs/exec-plans/active/*-implementation-lanes.md` | 否 |
| C | 原子工单 | `.scratch/<module>/issues/` | 否 |
| C | 测试用例矩阵 | `tech/<module>/test-matrix.md` | 否 |
| D | 前后端实现 | `backend/src/`、`frontend/src/` | 是（浏览器演示） |
| E | 矩阵执行结果（含手工项实际结果） | 回写 `test-matrix.md` | 是 |
| F | CI/CD 变更 + 部署配置登记 | `.github/workflows/`、`deploy/` | 否 |
| G | 复盘 + 里程碑状态 | `docs/retro/`、`docs/status/` | 是（阶段对齐检查点） |

无用户界面的模块（如纯后端能力）可以没有 `ui-spec.md` 与原型，但**"有没有界面"这个判断本身要说出来让用户否决**，不能默默省掉。`docs/retro/` 与 `docs/status/` 目前尚不存在，到 Step G 时按需创建（本项目约定不预建空目录骨架）。

## 7. Validation

- 单元测试：各模块后端业务逻辑单测（后续按模块技术方案确定框架，Spring Boot 默认用 JUnit）。
- 集成测试：各模块 API 契约级集成测试。
- E2E / smoke：`scripts/ci-validate.sh`（docker compose config 检查+文档链接检查+前端build+后端mvn verify）；每个模块开发完成后 `mvn spring-boot:run` + `pnpm dev` 实测浏览器操作。
- 文档一致性：`scripts/check-doc-links.mjs` 保证新增文档互链不失效。

## 8. Rollback

- 学习文档（`learning/`）与设计文档（`docs/`）均为增量新增，无需回滚机制；如分类需调整，直接编辑/移动文件。
- 代码实现按模块增量提交，若某模块设计有误，可在该模块对应的 `docs/exec-plans` 与 `.scratch` 工单中记录问题并重新设计，不影响已完成模块。

## 9. Decision Log

- `2026-07-24`: 计划创建并获用户批准。确认四项关键决策：① 参考策略=跟随课程+nageoffer/ragent开源仓库；② 任务书定位=还原企业级全流程、可作品集展示；③ 学习文档落位=新建顶层 `learning/ragent-column/` 工作区；④ 两条线节奏=按主题分组交替推进（学一组→建一个模块）。
- `2026-07-25`: Codex 接手 Claude 未完成工作。按最新索引校准为 91 篇正文、Phase 0 共 40 篇；接手时已完成 28 篇、待完成 12 篇。ADR 路径按当前领域建模规范统一为 `docs/adr/`，`.scratch/` 改为有真实工单时按需初始化。
- `2026-07-25`: Phase 0 学习内容达到 40/40，形成根词汇表、1 条独立建设 ADR 和对齐检查点。xrag 文档/前后端构建及存活端点通过；容器基础设施与完整 readiness 因本机缺少 Docker 未能执行，结果已如实记录。对齐前不进入 Phase 1 业务实现。
- `2026-07-25`: 用户确认 Phase 0 对齐检查点的四项决策——① Phase 1 前先补脚手架缺口；② 单用户个人项目，不做多租户/权限体系；③ pgvector 现在正式锁定为向量存储方案；④ Embedding 走云端 API 优先，本地 Ollama 仅作可选依赖。同时确认对象存储从 MinIO 换成 RustFS（专栏技术栈原文写的是 RustFS，MinIO 是脚手架初始化时的误选）。
- `2026-07-25`: 完成脚手架缺口修复（Phase 1 前置）：
  - `docker-compose.yml`/`deploy/compose/stack.compose.yml`/相关脚本与 CI 全部把对象存储服务从 `minio` 改名为 `rustfs`（镜像 `rustfs/rustfs:1.0.0-alpha.72`，端口 9000/9001，`RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`），健康检查探测 `/health`。已知风险：RustFS 仍是 alpha 版本，官方 issue 记录过 `CreateBucket` 偶发 503，详见 `learning/ragent-column/02-bootstrap-and-deployment/02-02-Docker本地中间件部署.md`。
  - `backend/`：新增 Maven Wrapper（`./mvnw`，固定 Maven 3.9.9）；接入 Flyway，`db/migration/V1__enable_pgvector.sql` 启用 pgvector 扩展；新增 `StorageConfig.ensureStorageBucket`（启动时幂等创建业务 Bucket）；新增 `FlywayConfig` 自定义 `FlywayMigrationStrategy`——两者都在依赖不可达时只记警告、不阻塞应用启动，与既有"应用始终能启动、readiness 反映真实依赖健康状态"的模式保持一致（曾因原生 Flyway 行为实测导致应用崩溃退出，已修正）；`HealthController.getReadiness` 改为逐项独立探测 Postgres/Redis/对象存储，失败时响应体仍完整展示每项状态，而不是折叠成一句笼统的 503。
  - `scripts/ci-validate.sh`：docker CLI 不存在时优雅跳过 `docker compose config` 检查（而不是直接失败），并改用 `./mvnw`；本机默认 JDK 不是 17 时自动尝试用 `/usr/libexec/java_home -v 17` 解析。
  - `scripts/infra-down.sh`：默认不再带 `-v` 删除数据卷，需要清空数据时显式传 `--wipe`。
  - 验证：`scripts/ci-validate.sh` 全绿（docker 检查已优雅跳过，因本机仍未安装 Docker）；`./mvnw verify` 通过；实际 `spring-boot:run` 验证过在零依赖存活的情况下应用正常启动，`/api/v1/health` 200，`/api/v1/health/ready` 503 且响应体逐项列出 postgres/redis/objectStorage 各自的失败原因。容器级端到端验证（真实 Postgres/Redis/RustFS 是否真的连通、pgvector 迁移是否真的执行成功、Bucket 是否真的建出来）仍需等用户装好 Docker 后补跑。
- `2026-07-25`: 用户装好并启动 Docker 后完成容器级端到端验证。过程中发现本机直连 `registry-1.docker.io` 会超时/被重置（其余域名如 GitHub/npm/Maven 均正常），判断为网络环境限制，通过给 Docker Desktop 配置 `registry-mirrors`（`docker.m.daocloud.io`/`docker.1ms.run`/`docker.xuanyuan.me`，已逐一验证可达）解决。验证结果：`docker compose up -d postgres redis rustfs` 三个容器全部健康；RustFS 的 `CreateBucket` 实测稳定（返回 200），未复现课程文章记录的 alpha 版 503 问题；真实 `spring-boot:run` 下 Flyway 成功迁移到 v1（`psql` 确认 `vector` 扩展 0.8.5 已启用）、`ensureStorageBucket` 成功自动建好 `app` Bucket、`/api/v1/health/ready` 返回 200 且 postgres/redis/objectStorage 三项均为 `ok`；`scripts/ci-validate.sh` 全流程（含真实 `docker compose config`）跑通。过程中发现并清理了一个由 IntelliJ 直接启动、独立于本次会话操作的旧后端进程（曾在 RustFS 就绪前启动，导致其自身的一次性建 Bucket 尝试落空）。Phase 0 收尾工作至此全部完成，可以进入 Phase 1（AI 知识库建设）的 PRD 与技术方案。
- `2026-07-25`: 完成 Phase 1 Step A（内容学习）+ Step B（PRD 起草）。抓取整理 03-knowledge-base 分组全部 11 篇文章（宏观设计、文件大小限制、内存优化/预签名URL、分布式限流两篇、文档上传/分块两篇接口、定时同步架构上下两篇、文档管理/分块管理两篇接口），全部沉淀到 `learning/ragent-column/03-knowledge-base/`，`INDEX.md` 同步更新（累计 51/91）。基于学习内容起草 [知识库建设 PRD](../../prd/2026-07-25-knowledge-base-prd.md)：明确目标/非目标（不做多租户、不做 Pipeline 模式、不做复杂平台鉴权）、四大功能域（知识库管理/文档管理/定时同步/分块管理）、非功能需求（上传安全、数据一致性）、5 条从学习内容提炼的关键设计决策、验收标准，以及 4 项显式留给技术方案阶段的待决策事项（异步触发机制选型、分块策略范围、定时同步锁机制的必要性重估、Embedding Provider 接入方式）。下一步：与用户对齐 PRD，再进入 Step C（技术方案 + 分角色任务书）。
- `2026-07-25`: 用户确认 PRD 通过，并追加一条**全系统级**约束：**所有业务数据的删除都是逻辑删除**。PRD 转为 approved 并新增 §7.6 记录该约束及其两项必要例外——向量数据必须物理删除（它是派生索引不是主数据，逻辑删除既无业务意义又会污染召回、拖慢检索），对象存储原始文件保留不删（逻辑删除意味着可恢复，删了源文件就恢复不回来）。受影响的 §4.2 删除条款、§4.4 分块删除、§8 验收标准同步修订。
- `2026-07-25`: 完成 Phase 1 Step C。产出：
  - **技术方案** [tech/knowledge-base/](../../../tech/knowledge-base/) 三件套——[architecture.md](../../../tech/knowledge-base/architecture.md)（分层与包结构、上传/分块/定时同步/回收四条核心流程、分块策略、Embedding 接入、上传安全）、[data-model.md](../../../tech/knowledge-base/data-model.md)（5 张表完整 DDL、状态机、级联逻辑删除的具体 SQL）、[api.md](../../../tech/knowledge-base/api.md)（19 个接口契约 + 统一错误模型）。
  - **[ADR 0002](../../adr/0002-knowledge-base-async-and-concurrency.md)**：把偏离课程方案的四项决策独立成档，每项都给出**等价性论证**而不是"个人项目凑合一下"——① 数据库任务表替代 RocketMQ 事务消息（事务消息解决的是跨异构系统的原子性，任务本身就是同库一行时该问题被消除而非被绕过）；② 状态字段 CAS 替代分布式锁（一条 `UPDATE ... WHERE` 原子地合并了检查与占用，锁要消除的窗口本就不存在）；③ 启动回收 + 心跳超时替代租约锁续期（租约锁的全部复杂度来自多实例间区分任务归属，单实例下该区分不存在且启动回收判断完全准确）；④ 本地信号量替代 Redisson。同时明确记录本决策依赖"单实例部署"假设、具体会在多实例下如何失效、以及届时的升级路径。
  - PRD §9 四项待决策事项全部结论化：异步用数据库任务表 + 2s 轮询；分块策略第一版支持 `FIXED_SIZE` + `RECURSIVE` 两种，语义分块推迟到 Phase 4（届时有评测数据能量化它值不值得）；不做租约锁；Embedding 定义最小 `EmbeddingClient` 接口、第一版走 OpenAI 兼容协议（一份实现覆盖百炼/智谱/硅基流动/Ollama，供应商配置化）。
  - **两项需用户确认的技术性收敛**（功能范围不变，但各放弃了具体能力）：向量存储用"单表 + `kb_id` 过滤"实现 PRD 说的"每知识库独立 collection"，代价是不同知识库不能用不同维度的 Embedding 模型；CONTEXT.md 的「文档版本」不建独立表、改用文档表自增 `revision` + 分块冗余 `revision` 承载，代价是不保留历史版本内容、不能回滚版本。
  - **[分角色任务书](2026-07-25-knowledge-base-implementation-lanes.md)**：产品/后端/前端/测试/运维五个 Lane，各自写清目标、写入范围（含**不得写入**的文件及原因）、依赖、完成定义（含验证命令）。
  - **19 张原子工单** `.scratch/knowledge-base/issues/`，按垂直切片（tracer bullet）组织并标注依赖边，附依赖图。Lane 是职责视角、工单是执行视角，同一批工作的两个切法。
  - 把 `tech/` 纳入 `scripts/check-doc-links.mjs` 扫描范围，链接校验通过。
  - **已识别的最高优先级风险**：技术方案没有直接照搬课程的预签名 URL 上传方案，而是先验证更简单的"临时文件 + `RequestBody.fromFile`"是否已经够用（学习笔记 03-03 的内存放大主要出现在 `fromBytes`/`fromInputStream`）。工单 07 把 30MB 上传的堆内存实测列为最高优先级手工验证，**这是唯一可能推翻技术方案的用例**，若不达标则退回预签名方案（退路已在 03-03 完整记录）。
  - 下一步：与用户确认上述两项技术性收敛，然后进入 Step D（开发落地），从工单 01/02/03 三个无阻塞项开始。**外部阻塞项：需要用户提供一个可用的 Embedding API Key，否则端到端链路只能验证到分块为止。**
- `2026-07-25`: **回头审计已完成 Step 的产出物，并把产出物闸门制度化。** 起因是用户发现交互原型缺失，要求"如果 Step 需要原型输出的场景务必不能跳过，需要输出原型让我确认交互"。逐项核对 Phase 0 全部 + Phase 1 Step A~D 的结论：
  - **只有原型一项是被真正静默跳过的**（已补，见下）。PRD、技术方案三件套、ADR 0002、分角色任务书、19 张工单、测试矩阵、学习整理稿、CONTEXT.md 均已产出；Phase 0 检查点 §6 列的脚手架缺口与 §7 待决策事项都已在 `2026-07-25` 的两条 Decision Log 中闭环。
  - **三项"尚未到期"而非漏做**：CI/CD 流水线设计（Step F，工单 19）、复盘 `docs/retro/`、里程碑状态 `docs/status/`（均 Step G）。后两个目录当前不存在，按本项目"不预建空目录骨架"的约定到时再建。
  - **一项待用户裁决**：早期批准的计划正文提到"产品需求评审记录"，目前该记录以 PRD 的 `status: approved` + 任务书 Decision Log 的形式存在，没有独立文档。是否需要独立成档由用户决定，不由我替判。
  - **一项顺带发现的不一致**：`AGENTS.md` 是 0 字节空文件（HEAD 中也为空），但 `scripts/check-doc-links.mjs` 把它列在扫描根里。不是计划产出物，未擅自填内容，留待用户决定是删掉还是写入内容。
  - **根因**：原型只出现在本计划 §6 的一句话概述里，从未落进任务书 Lane 产品的写入范围与完成定义，于是下游工单清单、Lane 完成定义、Decision Log 全都自洽地不含它——**任何自查都发现不了缺失，因为所有下游文档彼此一致**。批准的计划里"若需要可视化原型再用 Artifact 产出"这句判断权在用户手上，被我替用户判了且未声明。
  - **修复**：① 新增 §6.1 每模块产出物闸门表，逐 Step 列出必出产出物与是否需用户确认，并规定"有没有界面"这个判断本身也要说出来让用户否决；② 知识库任务书的 Lane 产品写入范围与完成定义硬性包含 `prototype/knowledge-base/`，前端 Lane 依赖改为"必须等原型经用户确认后才动手"；③ 工单 01 增加原型两项清单，状态从 done 退回"待用户确认原型"，工单 04 相应改为 `blocked-on-review`；④ `CLAUDE.md` 新增「产出物闸门（不可自行降级）」章节，把"计划里出现'若需要/可选'时判断权在用户手上"写成常驻约定。
