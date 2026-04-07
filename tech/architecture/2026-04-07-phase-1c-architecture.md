# xRag Phase 1C Architecture

**日期：** 2026-04-07  
**版本：** `v3 / Phase 1C`  
**状态：** draft  
**对应文档：**
- [Phase 1C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-1c-prd.md)
- [v3 Interaction Spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md)
- [Phase 1C Prototype](/Users/coderlauu/xRag/prototype/v3/index.html)
- [Phase 1B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md)

---

## 1. 文档目的

在 `Phase 1B` 已完成上传、文本型 PDF 解析、诊断和运维基线的前提下，为 `Phase 1C` 明确增量架构方案，重点回答：

- 扫描版 PDF OCR 如何接入当前 `upload -> parse -> search projection` 主链路
- 链接抓取如何作为新的导入来源进入统一文档状态机
- 搜索排序与匹配解释增强落在哪一层
- 文档处理时间线、诊断和运维可见性如何统一

本方案只覆盖 `Phase 1C`，不提前拉入 AI 问答、向量检索、多用户协作或浏览器插件。

---

## 2. Phase 1C 的真实边界

### 2.1 必须实现

- 扫描版 PDF OCR 解析闭环
- 链接正文抓取与入库闭环
- 搜索排序与匹配解释增强
- 文档处理时间线与诊断可见性增强

### 2.2 明确不做

- AI 问答
- 向量检索 / 语义召回
- 浏览器插件
- 团队协作 / 权限模型
- 移动端
- 全自动生产修复与自动发版

### 2.3 与 Phase 1B 的关系

`Phase 1C` 是在以下既有基线上增量演进：

- `web + api + worker + db + storage + queue`
- `Inbox / Search / Detail / Ops`
- 单对象与 multipart 上传
- PDF 文本解析与诊断码
- production 部署、观测、CI failure / auto repair 基线

---

## 3. 核心矛盾

`Phase 1B` 解决了“文件导入可稳定运行”，但还没有解决：

1. 扫描件没有文本层时如何继续处理
2. 链接内容如何进入统一文档主链路
3. 搜索结果为何命中、为何排序在前端无从解释
4. OCR、抓取、投影三个新阶段的运行证据如何暴露给用户与运维

---

## 4. 架构原则

1. `继续维护一条统一文档主链路，不为 OCR 或链接抓取开平行系统`
2. `对象存储继续作为文件事实源，数据库继续作为状态和诊断事实源`
3. `OCR 与链接抓取是解析阶段的扩展，而不是导入层的特例`
4. `搜索增强优先依赖结构化投影字段，不把排序逻辑埋在前端`
5. `时间线、诊断、事件三类证据用同一套稳定 code 和事件模型表达`
6. `生产保护优先用 feature flag / 降级，不优先回滚整套版本`

---

## 5. Phase 1C 总体架构

```text
Browser
  -> Web SPA
  -> API
      -> file upload session
      -> link ingest session
  -> PostgreSQL: document + session + diagnosis + timeline event
  -> Redis/BullMQ
      -> parse_document
      -> run_ocr
      -> fetch_link
      -> rebuild_search_projection
  -> Worker
      -> object fetch / link fetch
      -> OCR runtime /正文提取
      -> search projection builder
  -> PostgreSQL: document content + match explanation + event audit
  -> Web SPA polling / detail / ops

GitHub Actions
  -> CI / Failure Loop / Auto Repair
  -> incident artifact
  -> Ops Board consumption
```

---

## 6. 前端方案

### 6.1 技术栈

继续保持：

- `React 19`
- `TypeScript`
- `Vite`
- `TanStack Router`
- `TanStack Query`
- `Zustand`
- `React Hook Form + Zod`
- `Tailwind CSS + shadcn/ui`

原因：

- `Phase 1C` 的复杂度来自状态流和信息组织，而不是框架迁移
- 现有栈已经足够表达 URL state、异步轮询、时间线和多来源导入交互

### 6.2 前端新增模块

- `features/ocr`
  - OCR 状态、页数、失败说明
- `features/link-ingest`
  - 链接提交、抓取状态、失败诊断
- `features/search-explain`
  - 匹配解释、排序说明、命中来源
- `features/timeline`
  - 文档处理时间线

### 6.3 页面增量

- `Inbox`
  - 新增链接导入入口
  - 扫描件处理状态与 OCR backlog 可见
- `Search`
  - 新增 `source_type=link`
  - 新增 `match_explanation / ranking_hint / diagnosis_code`
- `Detail`
  - 新增 `processing timeline`
  - 新增 `OCR / link fetch` 细阶段
- `Ops`
  - 新增 `OCR runtime`、`抓取器`、`projection` 视角

### 6.4 前端容易忽略的点

- OCR 处理中不能让文档在搜索结果中“消失”
- 链接抓取失败需要明确告诉用户是否可以改成文本导入
- 匹配解释文案必须中文可读，不能直接暴露原始评分公式
- 时间线和诊断展示要和详情页、搜索页保持一致

---

## 7. 后端方案

### 7.1 API 新职责

- 创建链接导入会话
- 为扫描件标记是否需要 OCR
- 提供文档处理时间线读取接口
- 返回搜索匹配解释和排序增强字段

### 7.2 Worker 新职责

- 检测 PDF 是否有文本层
- 对扫描版 PDF 执行 OCR
- 抓取链接正文并提取标题、摘要、域名
- 在解析成功后构建搜索增强投影

### 7.3 OCR 方案

`Phase 1C` 只做单语言、服务端 OCR 基线：

- 先支持中文/英文混合文档的默认 OCR
- 先用同步结果写入 document，不做复杂版面分析
- 失败时返回稳定诊断码，而不是把所有 OCR 问题都归类为 `unsupported`

### 7.4 链接抓取方案

- 输入 URL 后先走白名单校验和基本格式校验
- Worker 抓取 HTML，再做正文提取
- 若命中反爬或正文为空，文档仍保留，但 `parse_status=failed`
- 链接抓取只做静态页面基线，不做动态渲染浏览器

### 7.5 搜索增强方案

排序与解释在后端生成，前端只负责展示：

- `match_explanation`
- `ranking_hint`
- `matched_fields`

这样可以保证搜索页和详情页使用同一套解释事实源。

### 7.6 后端容易忽略的点

- OCR 不应阻塞非扫描件主链路
- 链接抓取失败仍应保留文档记录和事件证据
- 处理时间线需要以稳定事件模型存储，不能只依赖日志拼装
- 搜索投影重建必须幂等，避免重复写入导致排序漂移

---

## 8. 测试策略

### 8.1 单元测试

- OCR 适配器成功 / 超时 / 空文本
- 链接正文提取成功 / 正文为空 / 反爬失败
- 匹配解释生成逻辑

### 8.2 集成测试

- 扫描件 PDF 上传 -> OCR -> 详情 / 搜索投影
- 链接导入 -> 抓取正文 -> 搜索可见
- 链接抓取失败 -> 诊断可见 -> 详情页可排查

### 8.3 E2E

- 用户上传扫描件并看到 OCR 状态推进
- 用户提交链接并在搜索页看到解释信息
- 用户从搜索跳到详情，看到完整时间线

### 8.4 Smoke

- deploy 后 `health`
- 真实 OCR 路径最小样例
- 链接抓取最小样例

---

## 9. 运维与上线

### 9.1 观测

- OCR backlog
- OCR 平均耗时
- 链接抓取失败按域名聚合
- 搜索投影刷新失败次数

### 9.2 降级策略

- 关闭 OCR feature flag，仅保留文本型 PDF
- 暂停链接抓取，只允许文本 / 文件导入
- 暂停排序增强，仅保留基础检索

### 9.3 回滚原则

- 优先功能降级
- 再考虑回切到 `Phase 1B stable`
- 保留诊断和时间线读取，不让排障能力一起消失

---

## 10. 我建议额外关注的点

- OCR 运行时依赖是否需要独立镜像层或系统包
- 链接抓取的网络出口、超时和 robots 策略
- 时间线事件表是否会快速膨胀，需要保留与清理策略
- 搜索解释对前端用户是否过度技术化，需要产品 copy 约束
