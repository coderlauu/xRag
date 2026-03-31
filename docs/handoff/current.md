# Current Handoff

当前默认阅读入口始终指向“本轮正在推进的版本 + 当前可执行技术方案”。

当前有效版本：[v1 / Phase 1A](/Users/coderlauu/xRag/docs/handoff/v1.md)

当前技术方案：

- [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
- [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
- [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

---

## 1. 本文档定位

以后给 coder agent 或 implementer 时，优先只给这一份文件：

`docs/handoff/current.md`

这份文档不再只是阅读索引，而是：

- 当前版本入口
- 技术方案摘要
- 文档缺口说明
- coding handoff 的默认起点

---

## 2. 先说明当前缺口

在这次补齐前，仓库存在以下缺口：

- `current.md` 只有阅读顺序，没有真正的技术方案
- `tech/architecture`、`tech/data-model`、`tech/api` 为空
- 原型中的 `localStorage / mock data / 前端内存搜索` 尚未替换成真实系统
- 多处绝对路径仍指向旧机器目录，影响 handoff 可用性

当前状态是：

- 技术方案、数据模型、API 设计已补齐
- `current.md` 已升级为正式 handoff 入口

---

## 3. 当前版本真实边界

### 必须实现

- 导入页 / 搜索页 / 详情页三页闭环
- 手动文本保存
- 文件上传
- 解析状态展示
- 关键词搜索
- 基础筛选
- 详情查看
- 空结果态与失败态

### 明确不做

- AI 问答
- 语义搜索
- 真实 OCR
- 浏览器插件
- 团队协作
- 移动端

### 原型中有但正式系统需重新实现

- 本地状态持久化
- PDF 成功 / 失败模拟逻辑
- 前端内存搜索
- mock 数据驱动

---

## 4. 架构师结论摘要

### 4.1 总体架构

采用 `前端 SPA + API 服务 + Worker 服务` 的前后端分离架构：

- `Web`：承载导入、搜索、详情三页
- `API`：处理文档、标签、上传、搜索、任务状态
- `Worker`：处理文件解析、重试、状态推进

### 4.2 前端技术栈与状态管理

推荐：

- `React 19`
- `TypeScript`
- `Vite`
- `TanStack Router`
- `TanStack Query`
- `Zustand`
- `React Hook Form`
- `Zod`
- `Tailwind CSS`

状态分层：

- `URL state`：搜索词、筛选条件、分页
- `Server state`：文档列表、详情、标签、任务状态
- `Client state`：toast、modal、临时 UI 状态
- `Form state`：文本导入、标签编辑、筛选表单

### 4.3 后端技术栈与持久化

推荐：

- `NestJS + Fastify + TypeScript`
- `Drizzle ORM`
- `PostgreSQL 16`
- `Redis + BullMQ`
- `S3-compatible Object Storage`

数据持久化边界：

- `PostgreSQL`：文档元数据、标签、任务审计、搜索字段
- `Object Storage`：原始文件
- `Redis`：异步队列，不做主数据源

### 4.4 异步任务与大文件处理

- 文件上传走 `预签名 URL / 直传对象存储`
- 上传完成后创建文档并入队解析任务
- Worker 流式读取文件并更新 `pending -> processing -> success/failed`
- 大文件通过 multipart upload 承接，避免 API 直接吞文件流量

### 4.5 搜索方案

`Phase 1A` 使用 `PostgreSQL pg_trgm + tsvector` 混合方案做关键词检索：

- 兼顾中文、英文和标题片段找回
- 暂不引入 `OpenSearch`
- 后续需要语义检索时再加 `pgvector` 或独立向量层

### 4.6 测试策略

至少覆盖：

- 单元测试
- 集成测试
- E2E 测试
- Contract Test
- 上传/解析 fixture test
- 部署后 smoke test

### 4.7 CI/CD

基线方案：

- `GitHub Actions` 承担 CI/CD
- PR 自动执行 `lint / typecheck / test / build`
- 合并到 `main` 自动部署 `staging`
- 通过 smoke test 后自动部署 `production` 或按 release tag 发布

如果团队已有 Harness 平台，可以把同一流程映射到 Harness Pipeline，但这不是前提条件。

---

## 5. Harness Engineering 规范

本项目可以按 `Harness Engineering` 的方式推进，重点不是“让 agent 替代设计”，而是让工程环境变得：

- 可读
- 可验证
- 可回放
- 可自动化

建议落地如下：

1. `repo 是 system of record`
   - PRD、交互、架构、数据模型、API、执行计划必须入库
2. `入口文档最小化`
   - `current.md` 只做目录和摘要，细节拆到独立文档
3. `计划文档版本化`
   - 复杂开发项先写 `docs/exec-plans/active/*.md`
4. `质量门禁可机检`
   - OpenAPI diff、链接检查、测试覆盖、镜像扫描都进 CI
5. `观测对 agent 和工程师都可见`
   - 日志、指标、trace、失败任务、构建产物可被统一读取

---

## 6. 当前建议阅读顺序

1. [v1 Handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)
2. [Phase 1A PRD](/Users/coderlauu/xRag/docs/prd/2026-03-31-xrag-phase-1a-prd.md)
3. [v1 Scope And Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-03-31-v1-scope-and-tradeoffs.md)
4. [v1 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-03-31-v1-interaction-spec.md)
5. [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
6. [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
7. [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
8. [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)
9. [产品交付 SOP](/Users/coderlauu/xRag/docs/process/product-delivery-sop.md)
10. [prototype/v1/index.html](/Users/coderlauu/xRag/prototype/v1/index.html)
11. [prototype/v1/search.html](/Users/coderlauu/xRag/prototype/v1/search.html)
12. [prototype/v1/detail.html](/Users/coderlauu/xRag/prototype/v1/detail.html)

---

## 7. 输出要求

当 coder / implementer 基于本版本开始正式开发时，至少应：

1. 先复述当前版本真实边界
2. 区分原型行为与真实系统能力
3. 按 `web / api / worker / db / storage / queue` 拆模块
4. 先实现关键词检索，不提前把 RAG 混进主链路
5. 让测试、CI/CD、观测和文档与代码一起落地

---

## 8. coding handoff 默认实施顺序

1. 初始化 monorepo 与基础 CI
2. 建 `documents / tags / uploads / jobs` 数据模型
3. 打通对象存储上传
4. 打通 worker 解析状态流
5. 实现搜索页与详情页 API
6. 接入前端三页
7. 补齐 unit / integration / e2e / smoke
8. 打通 staging 自动部署

---

## 9. 规则

- 新版本开始时，先复制 [handoff 模板](/Users/coderlauu/xRag/docs/handoff/_template.md)
- 生成对应的 `docs/handoff/vN.md`
- 再把 `current.md` 指向最新版本
- 历史版本 handoff 不覆盖，只归档
