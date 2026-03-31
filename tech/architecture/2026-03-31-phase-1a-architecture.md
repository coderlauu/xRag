# xRag Phase 1A Architecture

**日期：** 2026-03-31  
**版本：** `v1 / Phase 1A`  
**状态：** draft  
**对应文档：**
- [Phase 1A PRD](/Users/coderlauu/xRag/docs/prd/2026-03-31-xrag-phase-1a-prd.md)
- [v1 Scope And Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-03-31-v1-scope-and-tradeoffs.md)
- [v1 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-03-31-v1-interaction-spec.md)

---

## 1. 文档目的

把当前静态原型落为一个可交付、可测试、可部署、可持续演进的正式系统方案。

本方案只覆盖 `Phase 1A` 的真实实现，不把 `RAG`、`OCR`、`语义检索`、`多用户协作` 提前引入。

---

## 2. 真实边界与当前缺口

### 2.1 Phase 1A 真实边界

- 前后端分离
- 支持文本录入、文件上传、状态展示、关键词搜索、详情查看
- 支持 `pending / processing / success / failed`
- 支持标签、来源类型、时间范围等基础筛选
- 暂不做 AI 问答、语义搜索、OCR、浏览器插件、移动端

### 2.2 当前文档缺口

- 还没有正式的架构文档、数据模型文档、API 文档
- 还没有真实的异步任务设计与失败重试机制
- 还没有明确大文件上传链路与对象存储边界
- 还没有统一的测试金字塔与 CI/CD 方案
- 还没有把 `Harness Engineering` 方法落到 repo 结构和交付流程

### 2.3 本方案采用的关键假设

- 首期按 `单用户 / 单租户` 私有测试设计，数据模型预留 `owner_id` 扩展位
- 首期支持 `txt / md / 文本型 pdf`，扫描版 PDF 直接失败并给出原因
- 首期单文件大小建议上限 `50MB`，但上传架构按更大文件可扩展方案设计
- 检索目标是“稳定关键词找回”，不是“智能问答”
- 内容以中英混合文本为主，因此搜索不能只依赖纯英文分词方案

---

## 3. 架构原则

1. `一个真实闭环优先于多个半成品能力`
2. `一套主数据源优先于多份难同步副本`
3. `异步解析优先于请求内长耗时处理`
4. `对象存储与结构化元数据分离`
5. `搜索可升级，但 v1 不引入重型搜索集群`
6. `让仓库内文档成为 system of record，便于人和 agent 共同工作`

---

## 4. 技术选型总览

| 领域 | 选型 | 原因 |
| --- | --- | --- |
| Monorepo | `pnpm workspace` + `Turborepo` | 前后端分离但共享类型、脚本、OpenAPI client，适合 agent 和多人协作 |
| 前端 | `React 19` + `TypeScript` + `Vite` | 纯前后端分离 SPA，构建轻，开发反馈快 |
| 路由 | `TanStack Router` | URL 状态表达能力强，适合搜索页筛选场景 |
| 服务端状态 | `TanStack Query` | 统一请求缓存、轮询、失效、重试 |
| 客户端状态 | `Zustand` | 只承载短生命周期 UI 状态，不把服务端数据塞进全局 store |
| 表单 | `React Hook Form` + `Zod` | 表单复杂度适中，校验直观 |
| 样式 | `Tailwind CSS` + `shadcn/ui` | 能快速把静态原型转成正式组件，同时保留可维护性 |
| 后端 | `NestJS` + `Fastify` + `TypeScript` | 模块边界清晰，OpenAPI 友好，生态成熟 |
| ORM / SQL | `Drizzle ORM` + SQL migration | 对 PostgreSQL 扩展能力友好，适合 `pg_trgm`、GIN 索引等显式控制 |
| 主数据库 | `PostgreSQL 16` | 文档元数据、标签关系、状态流的统一主数据源 |
| 搜索 | `PostgreSQL pg_trgm + tsvector` 混合方案 | 兼顾中英关键词检索，避免 v1 过早引入 Elasticsearch/OpenSearch |
| 异步任务 | `BullMQ` + `Redis` | 文件解析、重试、死信、状态推进简单直接 |
| 大文件存储 | `S3-compatible object storage` | 文件与元数据解耦，本地可用 `MinIO`，线上可用 `AWS S3` |
| 观测 | `OpenTelemetry` + JSON logs + `Sentry` | 让请求、任务、异常都可追踪 |
| E2E | `Playwright` | 覆盖导入、状态、搜索、详情闭环 |
| CI/CD | `GitHub Actions` 为基线，`Harness Pipeline` 可选映射 | 先保证落地，再按团队基础设施升级 |

---

## 5. 推荐仓库结构

```text
xRag/
├── apps/
│   ├── web/                # React SPA
│   ├── api/                # NestJS API
│   └── worker/             # BullMQ worker
├── packages/
│   ├── api-client/         # OpenAPI 生成的前端 SDK
│   ├── shared-types/       # 通用类型与常量
│   ├── ui/                 # 共享 UI 组件
│   └── config/             # eslint/tsconfig/vitest/playwright 配置
├── infra/
│   ├── docker/
│   └── compose/
├── docs/
├── design/
├── tech/
└── prototype/
```

---

## 6. 系统架构

### 6.1 逻辑组件

- `Web SPA`
  - 承载导入页、搜索页、详情页
  - 负责上传发起、状态轮询、筛选与展示
- `API Service`
  - 提供文档、上传、标签、检索、任务状态接口
  - 写入数据库，签发对象存储上传凭证
- `Worker Service`
  - 消费解析任务
  - 拉取对象存储中的文件
  - 执行文本抽取、内容清洗、摘要截取、索引字段更新
- `PostgreSQL`
  - 持久化文档元数据、标签关系、任务审计、搜索字段
- `Redis`
  - BullMQ 队列、延迟重试、死信队列
- `Object Storage`
  - 存放原始上传文件与可选的解析中间产物

### 6.2 数据流

```text
Browser
  -> Web SPA
  -> API /uploads/initiate
  -> Object Storage (direct upload)
  -> API /uploads/{id}/complete
  -> PostgreSQL: document + upload metadata + parse_status=pending
  -> Redis/BullMQ: enqueue parse job
  -> Worker downloads object and parses
  -> PostgreSQL: content_clean/content_preview/search_text/parse_status
  -> Web SPA polls document/job status and refreshes UI
```

### 6.3 为什么不用同步上传即解析

- PDF 解析不稳定，时延和失败率都不可控
- 大文件会把 API 请求时间拉长
- 状态流是产品价值的一部分，不能被隐藏在超长请求里

---

## 7. 前端架构

### 7.1 技术栈

- `React 19`
- `TypeScript`
- `Vite`
- `TanStack Router`
- `TanStack Query`
- `Zustand`
- `React Hook Form`
- `Zod`
- `Tailwind CSS`
- `shadcn/ui`

### 7.2 状态管理分层

不要用一个大而全的全局 store。拆分如下：

- `URL state`
  - 由 `TanStack Router` 管理
  - 包含 `q / sourceType / parseStatus / tags / dateRange / page`
  - 好处是搜索条件可分享、可回退、可直接刷新恢复
- `Server state`
  - 由 `TanStack Query` 管理
  - 包含 `documents / document detail / tags / upload session / job status`
  - 好处是缓存、轮询、失效和请求状态统一
- `Client UI state`
  - 由 `Zustand` 管理
  - 仅放 toast、modal、拖拽上传状态、最近本地交互偏好
- `Form state`
  - 由 `React Hook Form + Zod` 管理
  - 文本导入表单、标签编辑表单、筛选表单都走这一层

### 7.3 页面模块拆分

- `apps/web/src/pages/inbox`
- `apps/web/src/pages/search`
- `apps/web/src/pages/detail`
- `apps/web/src/features/uploads`
- `apps/web/src/features/documents`
- `apps/web/src/features/tags`
- `apps/web/src/features/jobs`

### 7.4 与原型的映射

- 原型里的 `localStorage` 与 mock store 全部替换为 API + Query Cache
- 原型里的轮询式状态刷新保留，但迁移到 `TanStack Query refetchInterval`
- 原型里的搜索内存过滤改为 API 搜索接口

---

## 8. 后端架构

### 8.1 服务划分

- `apps/api`
  - HTTP API
  - OpenAPI 文档
  - DTO 校验
  - 业务服务
- `apps/worker`
  - 队列消费
  - 文本提取
  - 状态流推进
  - 错误记录与重试

### 8.2 核心模块

- `documents`
- `uploads`
- `search`
- `tags`
- `jobs`
- `health`
- `observability`

### 8.3 数据持久化策略

- `PostgreSQL` 持久化业务主数据
  - 文档主表
  - 标签表与关联表
  - 上传会话表
  - 解析任务表
- `Object Storage` 持久化原始文件
  - 原始文件不进数据库
  - 数据库存对象 key、mime、checksum、size
- `Redis` 只作为任务分发与短生命周期状态载体
  - 不是系统主存储

---

## 9. 搜索方案

### 9.1 v1 选择

`PostgreSQL pg_trgm + tsvector` 混合搜索。

### 9.2 原因

- 数据规模在 Phase 1A 可控
- 关键词找回是核心，但还不值得上独立搜索集群
- 中文搜索如果只依赖 `tsvector` 分词，效果不稳定
- `pg_trgm` 对中英混合、子串匹配、标题片段找回更友好

### 9.3 实现要点

- 维护 `search_text` 字段，拼接：
  - `title`
  - `content_clean`
  - `tags`
  - `file_name`
  - `source_url`
- 对 `search_text` 建 `GIN + gin_trgm_ops` 索引
- 额外维护 `search_vector` 用于英文/短词补充排序
- 排序规则：
  1. 标题精确命中
  2. 标签命中
  3. 内容命中
  4. 最近导入时间

### 9.4 升级路径

- 需要更复杂高亮、分析器、拼写纠错时，再引入 `OpenSearch`
- 需要语义检索时，再增加 `pgvector` 或专门向量索引层

---

## 10. 异步任务设计

### 10.1 任务类型

- `parse_document`
- `reparse_document`
- `refresh_search_projection`

### 10.2 状态流

`pending -> processing -> success | failed`

### 10.3 失败处理

- 可重试错误
  - 临时 IO 错误
  - 对象存储拉取失败
  - 队列消费超时
- 不可重试错误
  - 文件类型不支持
  - 扫描版 PDF 无法抽取文本
  - 文件内容为空

### 10.4 重试策略

- 指数退避 `3` 次
- 超过阈值进入 dead-letter queue
- 数据库保留最后一次失败原因，前端可直接展示

---

## 11. 大文件处理方案

### 11.1 上传链路

- 前端先请求 `/uploads/initiate`
- API 返回预签名 URL 或 multipart upload 参数
- 浏览器直传对象存储
- 上传完成后调用 `/uploads/{id}/complete`
- API 创建文档记录并投递解析任务

### 11.2 为什么这样做

- API 不需要承接大文件流量
- 前后端分离下更容易做断点续传
- 后续切换云厂商对象存储影响最小

### 11.3 解析链路

- Worker 从对象存储流式读取
- 文本型 `txt/md` 直接解析
- `pdf` 走专用文本抽取适配器
- 解析结果写回 `content_clean`、`content_preview`、`search_text`

### 11.4 安全与稳定性

- 按 MIME 和扩展名双重校验
- 计算 `sha256` 做去重基础
- 限制单文件大小、并发数、单任务执行时长
- 文件在对象存储开启服务端加密

---

## 12. 测试策略

### 12.1 单元测试

- 前端：组件、hooks、表单校验、搜索参数编码
- 后端：service、repository、任务处理器、状态机

### 12.2 集成测试

- API + PostgreSQL
- Worker + Redis + PostgreSQL
- 上传完成后的状态推进
- 标签编辑与搜索筛选联动

### 12.3 E2E 测试

使用 `Playwright` 覆盖最关键闭环：

1. 文本录入并成功搜索找回
2. 上传 `txt/md` 后进入 `success`
3. 上传文本型 `pdf` 后经历 `processing -> success`
4. 上传不支持文件后进入 `failed`
5. 搜索筛选与详情页跳转

### 12.4 补充测试

- `Contract test`：OpenAPI 变更校验，防止前后端协议漂移
- `Fixture test`：固定 PDF/TXT/MD 样本，保证解析输出稳定
- `Smoke test`：部署后跑最小链路验证

---

## 13. CI/CD

### 13.1 分支与环境

- `feature/*` -> PR Preview
- `main` -> 自动部署 `staging`
- `release tag` 或 `main` 经人工确认 -> 自动部署 `production`

### 13.2 CI 基线

- install
- lint
- typecheck
- unit test
- integration test
- build
- OpenAPI diff
- docker image build
- image scan

### 13.3 CD 基线

- 执行数据库 migration
- 部署 `api`
- 部署 `worker`
- 发布 `web`
- 运行 `smoke e2e`
- 失败自动回滚到上一版本

### 13.4 部署形态

- 本地：`Docker Compose`
- 线上基线：
  - `Web` 静态托管到 CDN
  - `API / Worker` 容器化部署
  - `PostgreSQL / Redis / Object Storage` 走托管服务

如果团队已使用 `Harness` 平台，可以把上述 CI/CD 流程映射为 `Build -> Verify -> Deploy -> Smoke -> Rollback` 流水线；如果没有，先用 `GitHub Actions` 落地即可。

---

## 14. Harness Engineering 落地方式

这里的 `Harness Engineering` 指的是一种 `agent-first / repo-first / feedback-loop-first` 的工程方法，而不是强依赖某个特定厂商产品。

### 14.1 Repo 作为唯一事实来源

- PRD、交互、架构、数据模型、API、执行计划全部入 repo
- 不把关键约束只放在聊天记录或口头同步里

### 14.2 入口文档最小化

- `docs/handoff/current.md` 只做目录和决策摘要
- 详细规则拆到 `tech/` 和 `docs/` 子文档
- 避免一个超长总文档快速腐化

### 14.3 可机检的质量门禁

- OpenAPI 必须由 CI 校验
- 文档链接可用性必须由 CI 校验
- 变更必须带测试证据
- 任务状态流必须有 fixture 和 integration coverage

### 14.4 执行计划入库

建议新增：

```text
docs/exec-plans/active/
docs/exec-plans/completed/
docs/generated/
```

- 大于 1 天的任务必须先写执行计划
- OpenAPI、ERD、DB schema snapshot 自动生成到 `docs/generated/`

### 14.5 提高 agent 可读性

- 提供短 `AGENTS.md` 作为目录，而不是写成百科全书
- 模块目录、脚本命名、测试入口保持稳定
- 所有常用验证命令统一收敛到 `pnpm validate`

---

## 15. 实施顺序建议

1. 初始化 monorepo、CI、基础容器和本地 compose
2. 落数据库 schema、上传链路、对象存储
3. 落 `documents / tags / uploads / jobs` API
4. 落 worker、状态流、失败重试
5. 落搜索接口与前端三页
6. 补齐 integration / e2e / smoke
7. 接入 staging 自动部署

---

## 16. 结论

Phase 1A 最合理的正式工程方案不是“先上 RAG”，而是：

- 用 `React SPA + NestJS API + Worker`
- 用 `PostgreSQL + Redis + S3-compatible storage`
- 用 `pg_trgm + tsvector` 做稳定关键词检索
- 用 `BullMQ` 承接异步解析和状态流
- 用 `Harness Engineering` 的文档化、可机检、可回放方式管理开发过程

这条路线复杂度可控，能真实替换掉当前原型中的 mock 行为，同时保留向 `OpenSearch / pgvector / OCR / RAG` 演进的空间。
