# xRag Phase 1B Architecture

**日期：** 2026-04-06  
**版本：** `v2 / Phase 1B`  
**状态：** draft  
**对应文档：**
- [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md)
- [v2 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- [Phase 1B Prototype](/Users/coderlauu/xRag/prototype/v2/index.html)
- [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)

---

## 1. 文档目的

在 `Phase 1A` 已稳定上线的工程基线之上，为 `Phase 1B` 明确增量架构方案，重点回答：

- `pdf` 真实解析如何接入
- multipart 上传如何与现有上传链路兼容
- 上传失败、解析失败和生产事故如何统一进入诊断闭环
- 前端、后端、测试、运维和上线治理如何一起推进

本方案只覆盖 `Phase 1B` 范围，不把 `OCR`、语义搜索、多租户协作提前拉进来。

---

## 2. Phase 1B 的真实边界

### 2.1 必须实现

- 文本型 `pdf` 真实解析
- multipart 大文件上传
- 上传完成后的对象完整性校验
- 失败分类与失败信息可见化
- production 观测与回滚最小基线

### 2.2 明确不做

- 扫描版 PDF OCR
- AI 问答
- 语义搜索
- 浏览器插件
- 多用户权限系统

### 2.3 与 Phase 1A 的关系

`Phase 1B` 不是重写，而是在以下已存在主链路上增量演进：

- `web + api + worker + db + storage + queue`
- `Inbox / Search / Detail`
- presigned upload
- `pending / processing / success / failed`
- `CI -> build-images -> deploy -> smoke`

---

## 3. 增量目标与核心矛盾

`Phase 1A` 证明了“最小闭环成立”，但还缺少生产可扩展性。`Phase 1B` 的架构重点不再是“页面能不能打开”，而是：

1. 上传要从单对象 PUT 演进为单对象和 multipart 双模式
2. 文件要从“部分文本类支持”演进为文本型 PDF 真实解析
3. 失败要从“红色状态”演进为“可定位、可解释、可补救”
4. 生产要从“可部署”演进为“可观测、可回滚、可排障”

---

## 4. 架构原则

1. `继续维护一条真实主链路，不用平行方案把系统分叉`
2. `上传状态与解析状态分离，但在文档详情中合并展示`
3. `对象存储是文件事实源，数据库是状态事实源`
4. `worker 负责内容提取，API 负责会话编排与状态落库`
5. `先把错误分类稳定下来，再扩展自动修复`
6. `先做半自动 incident loop，不做无人确认的全自动生产闭环`

---

## 5. Phase 1B 总体架构

```text
Browser
  -> Web SPA
  -> API / uploads/initiate
  -> Object Storage (single PUT or multipart parts)
  -> API / uploads/{id}/complete
  -> PostgreSQL: upload session + document + diagnosis state
  -> Redis/BullMQ: parse job
  -> Worker: object fetch -> pdf parse -> projection update
  -> PostgreSQL: parse result + diagnosis code + job audit
  -> Web SPA: polling / detail / ops board

GitHub Actions CI
  -> CI Failure Loop workflow
  -> incident artifact + issue
  -> Ops Board / runbook consumption
```

---

## 6. 前端方案

### 6.1 前端技术栈

保持 `Phase 1A` 技术栈不变：

- `React 19`
- `TypeScript`
- `Vite`
- `TanStack Router`
- `TanStack Query`
- `Zustand`
- `React Hook Form + Zod`
- `Tailwind CSS + shadcn/ui`

原因：

- `Phase 1B` 的复杂度主要来自状态流，不来自框架迁移
- 现有栈已足够表达 URL state、异步轮询和表单交互

### 6.2 前端新增模块

- `features/uploads`
  - 单对象上传
  - multipart 上传
  - 上传会话状态展示
- `features/diagnostics`
  - 失败分类 copy
  - 推荐动作
  - 失败详情面板
- `features/ops`
  - 服务健康
  - 最近 incident
  - 回滚信息

### 6.3 前端状态分层

- `URL state`
  - 搜索关键字、来源类型、解析状态、诊断类型、时间范围
- `server state`
  - 文档列表 / 详情
  - 上传会话
  - 任务状态
  - 运维摘要
- `client UI state`
  - 当前上传队列视图
  - 诊断抽屉开关
  - 失败提示与 toast

### 6.4 页面增量

- `Inbox`
  - 从“选中文件后上传”升级为“创建上传会话 -> 上传 -> complete -> 校验 -> 入队”
- `Search`
  - 增加 `diagnosis_code`、`upload_status` 维度
- `Detail`
  - 新增上传会话信息、失败分类、最近 job / incident 引用
- `Ops`
  - 仅作为内部页面或受保护页面暴露
  - 不要求在 `Phase 1B` 成为公共产品入口

### 6.5 前端容易忽略的点

- multipart 不能只做 happy path，要支持“失败后重试单个 part”
- 搜索页不能只展示 `parse_status`，要能区分“文件没传完”和“文件已传完但解析失败”
- 详情页要能解释“为什么失败”和“下一步该做什么”，而不只是显示一段错误字符串
- 运维页涉及内部信息，正式实现应加访问控制，不建议默认公开

---

## 7. 后端方案

### 7.1 API 服务增量职责

- 负责创建 upload session
- 决定单对象上传还是 multipart 上传
- 校验 complete 请求的完整性
- 生成文档与诊断状态
- 入队真实 parse job
- 提供诊断、运维和任务读取接口

### 7.2 Worker 服务增量职责

- 下载对象存储中的 PDF
- 对文本型 PDF 执行正文提取
- 生成 `content_clean / content_preview / search_text`
- 对失败分类并回写 `diagnosis_code / parse_error_message`

### 7.3 存储策略

- 原始文件继续放 `S3-compatible object storage`
- multipart 信息不进入 Redis，进入 PostgreSQL 作为审计和恢复依据
- complete 时必须做对象存在性校验和元数据核对

### 7.4 解析策略

`Phase 1B` 只做文本型 PDF：

- 优先选择稳定、纯 Node 或可控原生依赖的提取库
- 同一解析器实例禁止并发读取文本与元信息；按顺序调用，避免 `DataCloneError`
- 若检测为扫描版或无文本层：
  - 不进入 OCR
  - 明确返回 `pdf_parse_unsupported`

### 7.5 错误分类

后端和 worker 统一维护失败分类码，例如：

- `storage_presign_failed`
- `multipart_part_failed`
- `upload_complete_invalid_parts`
- `object_missing_on_complete`
- `pdf_parse_runtime_error`
- `pdf_parse_unsupported`
- `pdf_parse_timeout`
- `queue_backlog`

原则：

- 页面展示 copy 可以中文化
- 数据库存 code 保持稳定英文枚举，便于日志、告警和统计

### 7.6 后端容易忽略的点

- `complete` 必须幂等，否则前端重试会造成重复 document
- multipart part 清单不能完全信任客户端，服务端要校验 part number 和 etag
- 上传成功不等于对象可读，必须做对象存在性和大小校验
- PDF 提取要有最大页数、最大字节数和超时保护，避免 worker 被单个文件拖死
- 搜索投影更新要与解析成功事务边界明确，避免 `parse_status=success` 但搜索仍为空

---

## 8. 数据与状态机方案

### 8.1 拆分状态

`Phase 1A` 只有 `parse_status` 不够表达 `Phase 1B` 的真实链路，需要新增：

- `upload_status`
- `diagnosis_code`
- `diagnosis_summary`

### 8.2 建议状态机

上传状态：

`draft -> initiated -> uploading -> verifying -> uploaded -> failed`

解析状态：

`pending -> processing -> success | failed`

规则：

- `upload_status=uploaded` 但 `parse_status=failed` 是合法组合
- `upload_status=failed` 时不应创建 parse job
- `parse_status=failed` 且对象仍可读时，允许 `retry parse`

---

## 9. 搜索与详情读取方案

### 9.1 搜索结果

Phase 1B 搜索结果需要至少多带：

- `upload_status`
- `diagnosis_code`
- `diagnosis_summary`
- `latest_job_status`

### 9.2 详情页

详情页需要合并三个维度：

- 文档内容
- 上传会话
- 最近解析任务 / incident 引用

读取策略：

- 文档详情继续以 PostgreSQL 为唯一事实源
- 不直接从队列系统读取页面数据

---

## 10. 运维与观测方案

### 10.1 最小观测集

`Phase 1B` 至少落这四类：

1. 结构化日志
   - API request logs
   - upload logs
   - worker parse logs
   - deploy logs
2. 健康检查
   - API live / ready
   - worker queue connectivity
   - PostgreSQL
   - Redis
   - object storage
3. 失败聚合
   - diagnosis code 聚合
   - CI failure incident
4. 回滚入口
   - 当前 tag
   - 上一稳定 tag
   - 回滚脚本与步骤

### 10.2 Ops Board 边界

`Ops Board` 在 `Phase 1B` 更适合作为“内部运维视图”，不建议把所有内部错误直接对终端用户暴露。

正式实现建议：

- 默认 behind auth / allowlist / internal flag
- 读取聚合后的健康与 incident 数据
- 不直接暴露敏感环境变量或原始堆栈

### 10.3 CI Failure Loop 的位置

这条链不是业务主链路的一部分，但属于 `Phase 1B` 的生产稳态能力：

- 监听 `CI` 失败
- 收集 failed run context
- 生成 issue + artifact
- 为后续人工修复或 agent 修复提供入口

---

## 11. 测试方案

### 11.1 单元测试

- 上传 session builder
- multipart part 组合与校验
- diagnosis code 映射
- PDF parser adapter

### 11.2 集成测试

- `initiate -> upload -> complete -> parse` 闭环
- multipart complete 校验失败
- object missing on complete
- pdf parse unsupported
- retry parse

### 11.3 E2E

- 文本型 PDF 成功导入并进入搜索
- multipart 上传中断后恢复
- 失败文档在搜索页和详情页可见

### 11.4 Smoke

- deploy 后 health 全绿
- 最小上传链路可用
- Ops Board 核心读取接口可用

### 11.5 容易忽略的测试点

- 非 ASCII 文件名
- 重复点击 complete
- 单个 part 多次重传
- 大文件超时后的浏览器恢复行为
- worker 并发下的状态写回顺序

---

## 12. 运维与发布方案

### 12.1 发布策略

建议顺序：

1. 先发布数据库 migration
2. 再发布 API / worker
3. 最后发布 web

原因：

- web 可以兼容旧接口一段时间
- API / worker 必须先识别新状态和新字段

### 12.2 回滚策略

- 保留上一稳定镜像 tag
- 回滚不回退对象存储数据
- 若 migration 为增量兼容型，优先通过应用层回滚解决

### 12.3 安全与合规

容易忽略但必须纳入：

- bucket CORS 规则
- presigned URL 过期时间
- 上传 MIME 和扩展名双重校验
- 文件大小上限和 worker 资源限制
- 日志中避免落敏感 payload 和 presigned URL

---

## 13. 推荐实施顺序

主线程先完成：

1. 冻结 `Phase 1B` 数据模型增量
2. 冻结 API wire shape
3. 冻结 diagnosis code 与状态机

再并行拆 lane：

- `Lane A`: multipart upload
- `Lane B`: pdf parse worker
- `Lane C`: 前端诊断与详情页
- `Lane D`: observability / ops / rollback

---

## 14. 本方案之外但应提前记账的事项

这些不一定属于 `Phase 1B P0`，但如果现在不记，后面很容易踩坑：

- PDF 解析库体积与镜像大小
- multipart 失败对象的过期清理
- 失败重试次数与死信策略
- 运维页访问控制
- CI failure issue 去重与归档
- 对象存储公网域名和 Console 域名的长期治理
