# xRag Phase 1C Data Model

**日期：** 2026-04-07  
**版本：** `v3 / Phase 1C`  
**状态：** draft  
**对应架构：** [Phase 1C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md)

---

## 1. 设计目标

在 `Phase 1B` 数据模型基础上，补齐 `Phase 1C` 所需的：

- 扫描件 OCR 状态与结果
- 链接导入与抓取诊断
- 搜索匹配解释与排序增强字段
- 文档处理时间线事件

---

## 2. 增量原则

1. 不推翻 `documents / uploads / document_parse_jobs` 主结构
2. 用增量字段与新表表达 OCR、链接抓取和时间线
3. 稳定 code 存数据库，中文展示文案在应用层映射
4. 先满足主链路与诊断读取，不做过度统计模型

---

## 3. 核心变更

### 3.1 `documents` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `source_type` | `enum('text','file','pdf','link')` | 扩展支持链接来源 |
| `source_url` | `text nullable` | 链接导入原始 URL |
| `ocr_status` | `enum('not_required','queued','processing','success','failed') nullable` | OCR 子状态 |
| `ocr_engine` | `varchar(64) nullable` | OCR 引擎标识 |
| `ocr_language` | `varchar(64) nullable` | OCR 语言配置 |
| `matched_fields` | `jsonb nullable` | 命中字段列表，如 title/body/ocr_text |
| `match_explanation` | `text nullable` | 给前端直接展示的中文解释 |
| `ranking_hint` | `text nullable` | 排序摘要 |
| `timeline_cursor` | `varchar(64) nullable` | 最近时间线事件游标 |

说明：

- `parse_status` 继续作为总状态
- `ocr_status` 只在扫描件需要 OCR 时有值
- 链接导入成功后同样写 `documents`

### 3.2 新增 `document_source_fetches`

记录链接抓取与正文提取尝试。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `document_id` | `uuid` | FK -> documents.id |
| `source_url` | `text` | 抓取 URL |
| `fetch_status` | `enum('queued','fetching','extracting','success','failed')` | 抓取状态 |
| `http_status` | `integer nullable` | HTTP 状态码 |
| `content_type` | `varchar(128) nullable` | 响应类型 |
| `canonical_url` | `text nullable` | 规范化 URL |
| `title_extracted` | `varchar(255) nullable` | 抽取标题 |
| `diagnosis_code` | `varchar(64) nullable` | 抓取失败码 |
| `error_message` | `text nullable` | 失败说明 |
| `started_at` | `timestamptz nullable` | 开始时间 |
| `finished_at` | `timestamptz nullable` | 结束时间 |

### 3.3 新增 `document_processing_events`

作为统一时间线事实源。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `document_id` | `uuid` | FK -> documents.id |
| `event_type` | `varchar(64)` | 例如 `upload_completed`、`ocr_started`、`link_fetch_failed` |
| `stage` | `enum('upload','parse','ocr','fetch','projection','ops')` | 阶段分类 |
| `status` | `enum('pending','processing','success','failed')` | 事件状态 |
| `diagnosis_code` | `varchar(64) nullable` | 失败码 |
| `summary` | `text` | 中文摘要 |
| `payload` | `jsonb nullable` | 附加上下文，如页数、域名、分数 |
| `created_at` | `timestamptz` | 创建时间 |

索引：

- `(document_id, created_at desc)`
- `(stage, status)`

### 3.4 `document_parse_jobs` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job_type` | `enum('parse_document','run_ocr','fetch_link','rebuild_projection')` | 扩展任务类型 |
| `attempt_group` | `varchar(64) nullable` | 将同一条文档的多次处理串联 |

### 3.5 可选新增 `search_projection_snapshots`

如果需要调试排序与解释，可增加快照表。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `document_id` | `uuid` | FK -> documents.id |
| `rank_score` | `numeric(10,4)` | 排序分数 |
| `matched_fields` | `jsonb` | 命中字段 |
| `explanation` | `text` | 匹配解释 |
| `created_at` | `timestamptz` | 生成时间 |

`Phase 1C` 第一版可不单独落表，先把解释字段写回 `documents`。

---

## 4. 状态机

### 4.1 文件导入

`draft -> initiated -> uploading -> verifying -> uploaded`

然后：

- 文本型 PDF：`parse -> success/failed`
- 扫描件 PDF：`parse -> ocr -> projection -> success/failed`

### 4.2 链接导入

`initiated -> uploaded -> fetch -> extract -> projection -> success/failed`

说明：

- 链接导入没有对象存储分片上传，但为了统一详情页展示，仍沿用 `upload_status=uploaded`
- 真正的失败发生在 `fetch / extract`

### 4.3 OCR 状态

`not_required -> queued -> processing -> success | failed`

规则：

- 只有扫描件才进入 OCR 状态
- OCR 失败时 `parse_status=failed`

---

## 5. 诊断码建议

- `ocr_runtime_error`
- `ocr_timeout`
- `ocr_no_text_detected`
- `link_fetch_timeout`
- `link_fetch_blocked`
- `link_extract_empty`
- `link_invalid_url`
- `search_projection_stale`

页面 copy 使用中文映射，不把中文作为唯一事实源。

---

## 6. 索引建议

```sql
create index idx_documents_source_type
  on documents (source_type);

create index idx_documents_ocr_status
  on documents (ocr_status);

create index idx_document_source_fetches_document_id
  on document_source_fetches (document_id);

create index idx_document_processing_events_document_created
  on document_processing_events (document_id, created_at desc);

create index idx_document_processing_events_stage_status
  on document_processing_events (stage, status);
```

---

## 7. 幂等与一致性

### 7.1 链接提交

- 相同 `source_url` 可提示重复，但不默认拒绝
- 对同一文档的重抓取要写入新事件，不重建 document

### 7.2 OCR 重试

- 不创建新 document
- 创建新 `document_parse_jobs`
- 写新时间线事件

### 7.3 搜索投影

- 可以重复重建
- 以最后一次成功投影覆盖 `match_explanation / ranking_hint`

---

## 8. 数据保留

- `document_processing_events` 至少保留 `90` 天
- `document_source_fetches` 至少保留 `30-90` 天
- 事件表如快速膨胀，后续可做冷热分层

---

## 9. 容易忽略的数据问题

- 同一链接因参数不同产生重复内容
- OCR 成功但文本质量极差，仍需区分“成功但低质量”
- 链接正文抽取成功但内容为空白或极短
- 匹配解释使用了已过期的投影字段
- 时间线事件遗漏导致详情页展示断裂
