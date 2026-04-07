# xRag Phase 1B Data Model

**日期：** 2026-04-06  
**版本：** `v2 / Phase 1B`  
**状态：** draft  
**对应架构：** [Phase 1B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md)

---

## 1. 设计目标

在 `Phase 1A` 数据模型基础上，补齐 `Phase 1B` 所需的：

- multipart 上传会话
- 上传与解析分离状态
- 错误分类与诊断摘要
- 事件引用与运维读取能力

---

## 2. 增量原则

1. 不推翻 `documents / uploads / document_parse_jobs` 主结构
2. 尽量做向后兼容 migration
3. 错误分类用稳定 code，展示文案走应用层
4. 先满足读写闭环与审计，不做过度统计模型

---

## 3. 核心变更

### 3.1 `documents` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `upload_status` | `enum('draft','initiated','uploading','verifying','uploaded','failed') nullable` | 上传状态 |
| `diagnosis_code` | `varchar(64) nullable` | 稳定错误分类码 |
| `diagnosis_summary` | `text nullable` | 便于页面直接展示的摘要 |
| `upload_id` | `uuid nullable` | 对应上传会话 |
| `page_count` | `integer nullable` | 文本型 PDF 页数 |
| `parser_name` | `varchar(64) nullable` | 解析器标识 |
| `parser_version` | `varchar(64) nullable` | 解析器版本 |
| `last_incident_ref` | `varchar(64) nullable` | 最近事件引用 |

说明：

- `upload_status` 与 `parse_status` 共同表达主链路状态
- 文本文档可令 `upload_status=null`

### 3.2 `uploads` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `upload_mode` | `enum('single','multipart')` | 上传模式 |
| `status` | `enum('initiated','uploading','verifying','uploaded','failed','expired')` | 替代 Phase 1A 的简化状态 |
| `provider_upload_id` | `varchar(255) nullable` | S3 multipart upload id |
| `part_count` | `integer nullable` | 预期分片数 |
| `uploaded_part_count` | `integer default 0` | 已上传分片数 |
| `verified_at` | `timestamptz nullable` | 对象校验成功时间 |
| `error_code` | `varchar(64) nullable` | 上传阶段错误码 |
| `error_message` | `text nullable` | 上传阶段错误信息 |
| `completed_by_client_at` | `timestamptz nullable` | 客户端声明 complete 的时间 |

### 3.3 新增 `upload_parts`

记录 multipart part 审计。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `upload_id` | `uuid` | FK -> uploads.id |
| `part_number` | `integer` | part 序号 |
| `etag` | `varchar(255) nullable` | 对象存储返回的 etag |
| `size_bytes` | `bigint nullable` | part 大小 |
| `status` | `enum('initiated','uploaded','failed')` | part 状态 |
| `error_code` | `varchar(64) nullable` | part 失败码 |
| `error_message` | `text nullable` | part 失败说明 |
| `created_at` | `timestamptz` | 创建时间 |
| `updated_at` | `timestamptz` | 更新时间 |

唯一约束：

- `(upload_id, part_number)`

### 3.4 `document_parse_jobs` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `diagnosis_code` | `varchar(64) nullable` | 任务级诊断码 |
| `incident_ref` | `varchar(64) nullable` | 关联事件引用 |
| `worker_name` | `varchar(64) nullable` | 处理 worker |
| `runtime_ms` | `integer nullable` | 执行耗时 |

### 3.5 可选新增 `service_incidents`

如果 `Ops Board` 需要完全读库而不是读 GitHub，可新增本表。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `incident_ref` | `varchar(64)` | 展示用 ID |
| `source` | `enum('upload','parse','deploy','ci')` | 事件来源 |
| `severity` | `enum('low','medium','high')` | 严重级别 |
| `status` | `enum('open','tracked','resolved')` | 生命周期 |
| `title` | `varchar(255)` | 标题 |
| `summary` | `text` | 摘要 |
| `document_id` | `uuid nullable` | 关联文档 |
| `job_id` | `uuid nullable` | 关联任务 |
| `external_url` | `text nullable` | 如 GitHub issue / run 链接 |
| `created_at` | `timestamptz` | 创建时间 |
| `resolved_at` | `timestamptz nullable` | 关闭时间 |

`Phase 1B` 第一版可暂不落库；如果不落库，则 Ops Board 的 incidents 从 GitHub 或聚合接口读取。

---

## 4. 状态机

### 4.1 上传状态

单对象：

`initiated -> uploading -> verifying -> uploaded | failed`

multipart：

`initiated -> uploading -> verifying -> uploaded | failed | expired`

规则：

- `uploading` 表示至少一个 part 已开始上传
- `verifying` 表示客户端已 complete，服务端正在核对对象
- `failed` 表示当前上传会话不可继续，需用户重试或重建会话

### 4.2 解析状态

`pending -> processing -> success | failed`

规则：

- 只有 `upload_status=uploaded` 才能进入 `pending`
- `failed` 后允许重新入队，不必重建文档

---

## 5. 诊断码建议

统一使用稳定英文 code：

- `storage_presign_failed`
- `multipart_part_failed`
- `upload_complete_invalid_parts`
- `object_missing_on_complete`
- `pdf_parse_runtime_error`
- `pdf_parse_unsupported`
- `pdf_parse_timeout`
- `pdf_parse_empty_text`
- `queue_backlog`

页面 copy 从 code 映射中文说明，不把中文文案直接存为唯一事实源。

---

## 6. 索引建议

```sql
create index idx_documents_upload_status
  on documents (upload_status);

create index idx_documents_diagnosis_code
  on documents (diagnosis_code);

create index idx_uploads_status
  on uploads (status);

create index idx_uploads_provider_upload_id
  on uploads (provider_upload_id);

create unique index idx_upload_parts_upload_part_number
  on upload_parts (upload_id, part_number);

create index idx_document_parse_jobs_diagnosis_code
  on document_parse_jobs (diagnosis_code);
```

---

## 7. 幂等与一致性

### 7.1 initiate

- 相同会话请求不复用旧 upload id
- 但可通过 `checksum + file_size + owner_id` 提示可能重复

### 7.2 multipart part

- 同一 `part_number` 可被覆盖重传
- 最终以最后一次成功 etag 为准

### 7.3 complete

- 对同一 `upload_id` 重复调用必须幂等
- 若对象已校验成功，直接返回现有 `document_id`

### 7.4 parse retry

- 不创建新 document
- 只创建新 `document_parse_jobs`
- 更新 `documents.parse_status`

---

## 8. 数据保留

- `upload_parts` 可只保留 `30-90` 天
- `document_parse_jobs` 至少保留 `90` 天
- multipart 失败会话应有过期清理机制
- 原始对象删除前必须先确认无活跃重试链路

---

## 9. 容易忽略的数据问题

- 文件名与 MIME 不一致
- 非 ASCII 文件名
- 同一个对象上传成功但 `complete` 重试多次
- 扫描版 PDF 实际无文本层，但对象本身完全正常
- 解析成功但搜索投影尚未刷新导致“成功不可搜”
