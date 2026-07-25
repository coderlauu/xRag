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
- 每个模块产出完整企业级 SDLC 文档：PRD（`docs/prd/`）、原型/交互说明、技术方案（`tech/<module>/`）、分角色任务书（`docs/exec-plans/active/*-implementation-lanes.md`）、原子工单（`.scratch/`）、测试用例矩阵、CI/CD 流水线设计（`.github/workflows`）。
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
2. **Phase 1～4**（每个分组重复一次循环）：内容学习 → 产品侧任务书(PRD+原型) → 技术方案+分角色任务书(exec-plan Lanes) → 开发落地(前后端) → 测试与验收 → 运维/CI-CD → 复盘与状态更新 → 对齐检查点。依次覆盖：AI知识库建设 → 大模型调度引擎 → AI知识问答核心链路 → RAG评测体系。
3. **Phase 5**：消化"面试高频考点"分组；汇总全部阶段文档产出顶层系统说明书。

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
