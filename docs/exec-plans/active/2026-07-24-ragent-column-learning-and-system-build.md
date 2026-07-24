# Exec Plan: 知识星球《Ragent AI》专栏学习 + xrag 企业级 RAG 系统搭建

## 1. Metadata

- `plan_id`: 2026-07-24-ragent-column-learning-and-system-build
- `status`: active
- `owner`: liuqiang（本人学习+开发，AI 协作执行）
- `related_docs`: （待产出后补充链接）`learning/ragent-column/INDEX.md`、`CONTEXT.md`、各 `docs/prd/*`、各 `docs/exec-plans/active/*-implementation-lanes.md`

## 2. Objective

以知识星球专栏《Ragent AI》（约85篇文章，作者"马丁"，配套开源仓库 [nageoffer/ragent](https://github.com/nageoffer/ragent)）为学习材料，AI 辅助深度阅读、重新分类整理成结构化学习文档，并据此在当前 `xrag` 仓库（Java/Spring Boot + React 空白脚手架）中真正设计、实现一套企业级 Agentic RAG 系统，同时产出可作为作品集/简历材料的完整企业级 SDLC 文档（产品/前端/后端/测试/运维分角色任务书）。

## 3. Scope

### In Scope

- 知识星球专栏全部约85篇文章的深度学习与重新分类整理，产出 `learning/ragent-column/` 学习文档，兼具"AI 模型交接说明书"作用。
- 按学习内容分组，对应设计并实现系统模块（知识库建设、大模型调度引擎、知识问答核心链路、RAG评测体系等），在 `xrag` 仓库中真实开发落地（后端 Spring Boot + 前端 React）。
- 每个模块产出完整企业级 SDLC 文档：PRD（`docs/prd/`）、原型/交互说明、技术方案（`tech/<module>/`）、分角色任务书（`docs/exec-plans/active/*-implementation-lanes.md`）、原子工单（`.scratch/`）、测试用例矩阵、CI/CD 流水线设计（`.github/workflows`）。
- 领域术语与架构决策沉淀进根目录 `CONTEXT.md` 与 `docs/decisions/`（ADR）。
- 最终汇总产出顶层「系统说明书」。

### Out Of Scope

- 不对已删除的旧版 TypeScript 实现做迁移或恢复，仅作为设计参考对比（用户明确决策）。
- 不修改知识星球平台本身内容，仅只读学习。
- 暂不涉及生产环境真实上线（域名/密钥仍为占位值），CI/CD 设计以流水线本身的正确性为目标，非实际生产发布。

## 4. Assumptions

- 知识星球专栏内容可通过 App 内置浏览器面板（已登录态）持续访问，逐篇抓取正文；此访问方式在整个计划周期内保持可用。
- 用户愿意配合分阶段的高频对齐检查点（每个内容分组学习完成后、每个系统模块开发完成后）。
- 参考策略：主线跟随专栏课程 + `nageoffer/ragent` 开源代码；旧 TS 实现仅偶尔对比参考。
- 任务书定位为"还原企业级全流程、可作品集展示"，因此文档详细度高于一般学习笔记。

## 5. Risks

- 知识星球登录态可能过期/需要重新登录，导致抓取中断——发生时需用户重新确认登录。
- 85篇文章体量大，逐篇深度学习+产出企业级文档的总工作量很大，需要严格分阶段推进并在每个检查点确认，避免方向跑偏后大量返工。
- 专栏内容仍在持续更新（"面试中高频考点"分组标注"持续连载中"），最终分类和系统说明书可能需要在后续追更时调整。
- 原分类与我重新设计的系统模块分类之间可能存在文章内容跨模块的情况，需要在实际阅读后动态微调分组边界。

## 6. Plan

详见并行维护的计划文件 `~/.claude/plans/https-wx-zsxq-com-columns-51121244585524-transient-possum.md`（已获用户批准），核心阶段：

1. **Phase 0**：搭建 `learning/ragent-column/` 工作区骨架；抓取整理"RAG与大模型基础概念"(~24篇)+"本地开发与模型部署环境"(~10篇)两个基础分组；提炼 ADR/CONTEXT.md；环境实操验证；初始化 `.scratch/`。→ 对齐检查点。
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
